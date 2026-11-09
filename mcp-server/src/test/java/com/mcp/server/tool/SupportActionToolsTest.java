package com.mcp.server.tool;

import com.mcp.server.domain.Customer;
import com.mcp.server.domain.CustomerOrder;
import com.mcp.server.domain.Enums.PaymentStatus;
import com.mcp.server.domain.Enums.RefundStatus;
import com.mcp.server.domain.Enums.RefundType;
import com.mcp.server.domain.Payment;
import com.mcp.server.domain.Refund;
import com.mcp.server.dto.SupportDtos.RefundResult;
import com.mcp.server.repository.OrderRepository;
import com.mcp.server.repository.PaymentRepository;
import com.mcp.server.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportActionToolsTest {

    private static final String OWNER = "priya@example.com";

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final List<Refund> existing = new ArrayList<>();

    private SupportActionTools tools;
    private CustomerOrder order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        tools = new SupportActionTools(null, null, orders, payments, refunds, null, new BigDecimal("50"));

        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "email", OWNER);
        order = order(1L, customer);
        payment = payment(10L, order, "100.00", "TXN-1");
        ReflectionTestUtils.setField(order, "payments", List.of(payment));

        when(orders.findByOrderNumber("4471")).thenReturn(Optional.of(order));
        when(payments.findByTransactionRef("TXN-1")).thenReturn(Optional.of(payment));
        when(refunds.findByOrderAndStatusNot(order, RefundStatus.REJECTED)).thenReturn(existing);
        when(refunds.save(any(Refund.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void smallPartialRefundIsProcessedAndMarksPaymentPartiallyRefunded() {
        RefundResult result = refund(OWNER, "20.00", "TXN-1");

        assertEquals("PROCESSED", result.status());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
    }

    @Test
    void smallRefundOfWholePaymentMarksPaymentRefunded() {
        ReflectionTestUtils.setField(payment, "amount", new BigDecimal("20.00"));

        refund(OWNER, "20.00", "TXN-1");

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void refundAboveLimitWaitsForApprovalAndLeavesPaymentAlone() {
        RefundResult result = refund(OWNER, "60.00", "TXN-1");

        assertEquals("REQUESTED", result.status());
        assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
    }

    @Test
    void smallRefundsCannotAddUpPastTheLimitWithoutApproval() {
        existing.add(existingRefund("40.00", null));

        assertEquals("REQUESTED", refund(OWNER, "20.00", null).status());
    }

    @Test
    void retryOfSameRefundIsRejected() {
        existing.add(existingRefund("20.00", payment));

        assertRejected(OWNER, "20.00", "TXN-1");
    }

    @Test
    void refundOverWhatWasPaidIsRejected() {
        assertRejected(OWNER, "100.01", null);
    }

    @Test
    void refundsOverPaidAmountInTotalAreRejected() {
        existing.add(existingRefund("90.00", null));

        assertRejected(OWNER, "20.00", null);
    }

    @Test
    void zeroNegativeAndFractionOfCentAmountsAreRejected() {
        assertRejected(OWNER, "0", null);
        assertRejected(OWNER, "-5.00", null);
        assertRejected(OWNER, "1.005", null);
    }

    @Test
    void orderOfAnotherCustomerIsRejected() {
        assertRejected("attacker@example.com", "20.00", null);
    }

    @Test
    void paymentFromAnotherOrderIsRejected() {
        when(payments.findByTransactionRef("TXN-2"))
                .thenReturn(Optional.of(payment(20L, order(2L, new Customer()), "100.00", "TXN-2")));

        assertRejected(OWNER, "20.00", "TXN-2");
    }

    @Test
    void alreadyRefundedPaymentIsRejected() {
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.REFUNDED);

        assertRejected(OWNER, "20.00", "TXN-1");
    }

    @Test
    void refundOverWhatIsLeftOnPaymentIsRejected() {
        Payment second = payment(11L, order, "100.00", "TXN-3");
        ReflectionTestUtils.setField(order, "payments", List.of(payment, second));
        ReflectionTestUtils.setField(payment, "amount", new BigDecimal("30.00"));

        assertRejected(OWNER, "40.00", "TXN-1");
        verify(refunds, never()).save(any());
    }

    private RefundResult refund(String email, String amount, String transactionRef) {
        return tools.issueRefund(email, "4471", new BigDecimal(amount), RefundType.OTHER, "test", transactionRef);
    }

    private void assertRejected(String email, String amount, String transactionRef) {
        assertThrows(IllegalArgumentException.class, () -> refund(email, amount, transactionRef));
        verify(refunds, never()).save(any());
    }

    private static CustomerOrder order(Long id, Customer customer) {
        CustomerOrder order = new CustomerOrder();
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "customer", customer);
        ReflectionTestUtils.setField(order, "currency", "USD");
        return order;
    }

    private static Payment payment(Long id, CustomerOrder order, String amount, String ref) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "order", order);
        ReflectionTestUtils.setField(payment, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(payment, "transactionRef", ref);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.CAPTURED);
        return payment;
    }

    private static Refund existingRefund(String amount, Payment payment) {
        Refund refund = new Refund();
        refund.setAmount(new BigDecimal(amount));
        refund.setPayment(payment);
        return refund;
    }
}
