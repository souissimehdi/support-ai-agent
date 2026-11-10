package com.mcp.server.tool;

import com.mcp.server.domain.Customer;
import com.mcp.server.domain.CustomerOrder;
import com.mcp.server.domain.OrderItem;
import com.mcp.server.domain.Product;
import com.mcp.server.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportQueryToolsTest {

    private static final String OWNER = "priya@example.com";

    private final OrderRepository orders = mock(OrderRepository.class);
    private final SupportQueryTools tools = new SupportQueryTools(null, null, orders, null, null);
    private final Product product = new Product();

    @BeforeEach
    void setUp() {
        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "email", OWNER);
        OrderItem item = new OrderItem();
        ReflectionTestUtils.setField(item, "product", product);
        CustomerOrder order = new CustomerOrder();
        ReflectionTestUtils.setField(order, "customer", customer);
        ReflectionTestUtils.setField(order, "orderDate", LocalDate.now());
        ReflectionTestUtils.setField(order, "items", List.of(item));
        when(orders.findByOrderNumber("4471")).thenReturn(Optional.of(order));
    }

    @Test
    void productBoughtTodayIsInWarranty() {
        ReflectionTestUtils.setField(product, "warrantyMonths", 12);

        assertTrue(tools.checkWarranty(OWNER, "4471", null).inWarranty());
    }

    @Test
    void productWithNoWarrantyIsNeverInWarranty() {
        ReflectionTestUtils.setField(product, "warrantyMonths", 0);

        assertFalse(tools.checkWarranty(OWNER, "4471", null).inWarranty());
    }

    @Test
    void orderOfAnotherCustomerIsNotShown() {
        assertThrows(IllegalArgumentException.class, () -> tools.getOrderByNumber("mallory@example.com", "4471"));
        assertThrows(IllegalArgumentException.class, () -> tools.detectDuplicateCharges("mallory@example.com", "4471"));
        assertThrows(IllegalArgumentException.class, () -> tools.checkWarranty("mallory@example.com", "4471", null));
    }
}
