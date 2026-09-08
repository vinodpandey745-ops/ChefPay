package com.chefpay.server.delivery;

import com.chefpay.core.domain.DeliveryBoy;
import com.chefpay.core.repository.DeliveryBoyRepository;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.delivery.DeliveryBoyDtos.CreateDeliveryBoyRequest;
import com.chefpay.server.delivery.DeliveryBoyDtos.DeliveryBoyDto;
import com.chefpay.server.delivery.DeliveryBoyDtos.UpdateDeliveryBoyRequest;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Delivery rider roster CRUD (Round 9) - assignable to orders via {@code
 * OrderController#assignDeliveryBoy}, gated overall by {@code Restaurant#deliveryBoyFeatureEnabled}
 * (a restaurant-level display toggle - see {@code OrderService}'s own note that it only controls
 * whether the client shows the assignment control) and, since Bistrodesk Phase 4 (requirement #24),
 * ALSO by the {@code DELIVERY_MANAGEMENT} plan feature ({@link RequiresFeature}) - the two are
 * independent layers: the plan decides whether this install is entitled to delivery management at
 * all, the restaurant's own toggle decides whether ITS staff currently see the control day to day. */
@RestController
@RequestMapping("/api/delivery-boys")
@RequiredArgsConstructor
@RequiresFeature("DELIVERY_MANAGEMENT")
public class DeliveryBoyController {

    private final DeliveryBoyRepository deliveryBoyRepository;

    /** Front-of-house staff need to see (and pick from) this roster to assign a rider to an order
     * even though only a manager can edit it, so read is gated on either the manage permission or
     * the general order-modify permission - not just DELIVERY_MANAGE. */
    @GetMapping
    @PreAuthorize("hasAuthority('DELIVERY_MANAGE') or hasAuthority('ORDER_MODIFY')")
    public ApiResponse<List<DeliveryBoyDto>> list() {
        return ApiResponse.ok(deliveryBoyRepository.findAll().stream().map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DELIVERY_MANAGE')")
    public ApiResponse<DeliveryBoyDto> create(@Valid @RequestBody CreateDeliveryBoyRequest request) {
        DeliveryBoy deliveryBoy = DeliveryBoy.builder()
                .name(request.name())
                .phone(request.phone())
                .build();
        DeliveryBoy saved = deliveryBoyRepository.save(deliveryBoy);
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('DELIVERY_MANAGE')")
    @Transactional
    public ApiResponse<DeliveryBoyDto> update(@PathVariable UUID id, @RequestBody UpdateDeliveryBoyRequest request) {
        DeliveryBoy deliveryBoy = deliveryBoyRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Delivery boy not found"));
        if (deliveryBoy.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(DeliveryBoy.class, id);
        }

        if (request.name() != null) deliveryBoy.setName(request.name());
        if (request.phone() != null) deliveryBoy.setPhone(request.phone());
        if (request.active() != null) deliveryBoy.setActive(request.active());

        DeliveryBoy saved = deliveryBoyRepository.save(deliveryBoy);
        return ApiResponse.ok(toDto(saved));
    }

    private DeliveryBoyDto toDto(DeliveryBoy d) {
        return new DeliveryBoyDto(d.getId(), d.getName(), d.getPhone(), d.isActive(), d.getVersion());
    }
}
