package com.chefpay.server.duepayments;

import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.PaymentStatus;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.server.duepayments.DuePaymentDtos.DuePaymentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read-aggregation over billed-but-not-fully-paid orders (requirement Round 8 Due Payment Management). */
@Service
@RequiredArgsConstructor
public class DuePaymentService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<DuePaymentDto> listDuePayments() {
        return orderRepository
                .findByPaymentStatusInAndBilledAtIsNotNullOrderByBilledAtAsc(
                        List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID))
                .stream()
                .map(this::toDto)
                .toList();
    }

    private DuePaymentDto toDto(Order order) {
        return new DuePaymentDto(
                order.getId(),
                order.getOrderNumber(),
                order.getTable() == null ? null : order.getTable().getName(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                order.getTotalAmount(),
                order.getPaymentStatus().name(),
                order.getBilledAt());
    }
}
