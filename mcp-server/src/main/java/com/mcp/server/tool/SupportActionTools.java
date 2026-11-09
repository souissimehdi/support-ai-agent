package com.mcp.server.tool;

import com.mcp.server.domain.Customer;
import com.mcp.server.domain.CustomerOrder;
import com.mcp.server.domain.Enums.Channel;
import com.mcp.server.domain.Enums.Intent;
import com.mcp.server.domain.Enums.PaymentStatus;
import com.mcp.server.domain.Enums.RefundStatus;
import com.mcp.server.domain.Enums.RefundType;
import com.mcp.server.domain.Enums.Sentiment;
import com.mcp.server.domain.Enums.TicketStatus;
import com.mcp.server.domain.Payment;
import com.mcp.server.domain.Refund;
import com.mcp.server.domain.SupportTicket;
import com.mcp.server.dto.SupportDtos.RefundResult;
import com.mcp.server.dto.SupportDtos.TicketLogResult;
import com.mcp.server.repository.CustomerRepository;
import com.mcp.server.repository.OrderRepository;
import com.mcp.server.repository.PaymentRepository;
import com.mcp.server.repository.ProductRepository;
import com.mcp.server.repository.RefundRepository;
import com.mcp.server.repository.SupportTicketRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * State-changing MCP tools: the actions the agent <em>takes</em> once it has
 * decided on a resolution — issue a refund (and mark the reversed payment) and
 * log the interaction as a support ticket. Every method here writes to the DB.
 */
@Service
@Transactional
public class SupportActionTools {

    /** Payments where the customer's money was actually taken, including any already refunded. */
    private static final Set<PaymentStatus> REFUNDABLE_OR_REFUNDED =
            Set.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final SupportTicketRepository tickets;
    private final BigDecimal autoApproveLimit;

    public SupportActionTools(CustomerRepository customers,
                              ProductRepository products,
                              OrderRepository orders,
                              PaymentRepository payments,
                              RefundRepository refunds,
                              SupportTicketRepository tickets,
                              @Value("${support.refund.auto-approve-limit:50}") BigDecimal autoApproveLimit) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.tickets = tickets;
        this.autoApproveLimit = autoApproveLimit;
    }

    // ---------------------------------------------------------------------
    // Take action: issue a refund
    // ---------------------------------------------------------------------

    @McpTool(name = "issue_refund",
            description = "Issue a refund against an order and record it. Optionally tie it to a "
                    + "specific charge by transaction reference (e.g. when reversing one half of "
                    + "a duplicate charge), which updates that payment's status. This takes real "
                    + "action — only call once you have decided a refund is warranted. The result "
                    + "status is PROCESSED if the refund went through, or REQUESTED if it is waiting "
                    + "for a person to approve it and no money has moved yet.")
    public RefundResult issueRefund(
            @McpToolParam(description = "The sender address of the email being handled; the order must belong to it")
            String customerEmail,
            @McpToolParam(description = "The order number to refund against, e.g. 4471")
            String orderNumber,
            @McpToolParam(description = "The amount to refund, e.g. 199.99")
            BigDecimal amount,
            @McpToolParam(description = "Why the money is going back")
            RefundType refundType,
            @McpToolParam(description = "A short human-readable reason for the refund")
            String reason,
            @McpToolParam(required = false, description = "Transaction reference of the specific "
                    + "charge being reversed; optional. If given, that payment's status is updated.")
            String transactionRef) {
        CustomerOrder order = requireOrder(orderNumber);
        if (!order.getCustomer().getEmail().equalsIgnoreCase(customerEmail)) {
            throw new IllegalArgumentException("Order " + orderNumber + " does not belong to " + customerEmail);
        }
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Refund amount must be positive with at most 2 decimals: " + amount);
        }

        List<Refund> active = refunds.findByOrderAndStatusNot(order, RefundStatus.REJECTED);
        // A retried email runs the whole agent again, so an identical refund must not go through twice.
        if (active.stream().anyMatch(r -> r.getAmount().compareTo(amount) == 0)) {
            throw new IllegalArgumentException(
                    "A refund of " + amount + " already exists on order " + orderNumber + "; not creating another.");
        }
        BigDecimal orderRefunded = sum(active.stream().map(Refund::getAmount));
        BigDecimal paid = sum(order.getPayments().stream()
                .filter(p -> REFUNDABLE_OR_REFUNDED.contains(p.getStatus()))
                .map(Payment::getAmount));
        BigDecimal orderTotal = orderRefunded.add(amount);
        if (orderTotal.compareTo(paid) > 0) {
            throw new IllegalArgumentException("Refund of " + amount + " exceeds what is left to refund on order "
                    + orderNumber + " (paid " + paid + ", already refunded " + orderRefunded + ").");
        }
        RefundStatus status = orderTotal.compareTo(autoApproveLimit) > 0
                ? RefundStatus.REQUESTED
                : RefundStatus.PROCESSED;

        Refund refund = new Refund();
        refund.setOrder(order);
        refund.setAmount(amount);
        refund.setCurrency(order.getCurrency());
        refund.setReason(reason);
        refund.setRefundType(refundType);
        refund.setStatus(status);

        if (transactionRef != null && !transactionRef.isBlank()) {
            Payment payment = payments.findByTransactionRef(transactionRef.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No payment found with transaction reference: " + transactionRef));
            if (!payment.getOrder().getId().equals(order.getId())) {
                throw new IllegalArgumentException(
                        "Payment " + transactionRef + " does not belong to order " + orderNumber);
            }
            if (payment.getStatus() != PaymentStatus.CAPTURED
                    && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
                throw new IllegalArgumentException(
                        "Payment " + transactionRef + " cannot be refunded; its status is " + payment.getStatus());
            }
            BigDecimal paymentTotal = amount.add(sum(active.stream()
                    .filter(r -> r.getPayment() != null && r.getPayment().getId().equals(payment.getId()))
                    .map(Refund::getAmount)));
            if (paymentTotal.compareTo(payment.getAmount()) > 0) {
                throw new IllegalArgumentException("Refund of " + amount + " exceeds what is left on payment "
                        + transactionRef + " (charged " + payment.getAmount() + ").");
            }
            // A refund waiting for approval has not moved any money, so the payment stays as it is.
            if (status == RefundStatus.PROCESSED) {
                payment.setStatus(paymentTotal.compareTo(payment.getAmount()) == 0
                        ? PaymentStatus.REFUNDED
                        : PaymentStatus.PARTIALLY_REFUNDED);
            }
            refund.setPayment(payment);
        }

        Refund saved = refunds.save(refund);
        String outcome = status == RefundStatus.PROCESSED
                ? "processed"
                : "waiting for human approval, no money has moved yet,";
        String summary = "Refund of %s %s %s on order %s (%s): %s"
                .formatted(amount, order.getCurrency(), outcome, orderNumber, refundType, reason);
        return new RefundResult(saved.getId(), orderNumber, amount, order.getCurrency(),
                refundType.name(), saved.getStatus().name(), summary);
    }

    // ---------------------------------------------------------------------
    // Record what happened: log the ticket
    // ---------------------------------------------------------------------

    @McpTool(name = "log_support_ticket",
            description = "Record this email interaction as a support ticket, capturing the raw "
                    + "message, detected language, classified intent/sentiment, and the "
                    + "resolution. Call this last to log what was decided and done.")
    public TicketLogResult logSupportTicket(
            @McpToolParam(description = "Email of the customer the ticket is for")
            String customerEmail,
            @McpToolParam(description = "The customer's original message, verbatim")
            String rawMessage,
            @McpToolParam(description = "What the email is about")
            Intent intent,
            @McpToolParam(description = "The customer's tone")
            Sentiment sentiment,
            @McpToolParam(description = "Short subject line summarising the email")
            String subject,
            @McpToolParam(description = "Detected language code(s), e.g. en, hi, or en+hi")
            String detectedLanguage,
            @McpToolParam(description = "What was decided/done and the gist of the reply sent back")
            String resolution,
            @McpToolParam(required = false, description = "Related order number, if any")
            String orderNumber,
            @McpToolParam(required = false, description = "Related product SKU, if any")
            String sku) {
        Customer customer = customers.findByEmailIgnoreCase(customerEmail.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No customer account found for email: " + customerEmail));

        SupportTicket ticket = new SupportTicket();
        ticket.setCustomer(customer);
        ticket.setChannel(Channel.EMAIL);
        ticket.setRawMessage(rawMessage);
        ticket.setIntent(intent);
        ticket.setSentiment(sentiment);
        ticket.setSubject(subject);
        ticket.setDetectedLanguage(detectedLanguage);
        ticket.setResolution(resolution);
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(LocalDateTime.now());

        if (orderNumber != null && !orderNumber.isBlank()) {
            orders.findByOrderNumber(orderNumber.trim()).ifPresent(ticket::setOrder);
        }
        if (sku != null && !sku.isBlank()) {
            products.findBySkuIgnoreCase(sku.trim()).ifPresent(ticket::setProduct);
        }

        SupportTicket saved = tickets.save(ticket);
        return new TicketLogResult(saved.getId(), saved.getStatus().name(),
                "Logged ticket #%d for %s.".formatted(saved.getId(), customerEmail));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private CustomerOrder requireOrder(String orderNumber) {
        return orders.findByOrderNumber(orderNumber.trim())
                .orElseThrow(() -> new IllegalArgumentException("No order found with number: " + orderNumber));
    }

    private static BigDecimal sum(Stream<BigDecimal> amounts) {
        return amounts.reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
