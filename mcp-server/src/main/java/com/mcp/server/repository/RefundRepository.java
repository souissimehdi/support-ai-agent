package com.mcp.server.repository;

import com.mcp.server.domain.Refund;
import com.mcp.server.domain.CustomerOrder;
import com.mcp.server.domain.Enums.RefundStatus;
import com.mcp.server.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByOrderAndStatusNot(CustomerOrder order, RefundStatus status);
}
