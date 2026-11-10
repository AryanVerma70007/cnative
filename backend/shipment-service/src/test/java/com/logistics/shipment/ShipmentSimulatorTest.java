package com.logistics.shipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ShipmentSimulatorTest {
  @Test
  void advancesCreatedShipmentWithLocationAndTracking() {
    var repo = mock(ShipmentRepository.class);
    var states = mock(ShipmentStateRepository.class);
    var rabbit = mock(RabbitTemplate.class);
    var shipment = shipment("CREATED");
    when(repo.findByStatusNot("DELIVERED")).thenReturn(List.of(shipment));
    when(repo.findAll()).thenReturn(List.of(shipment));

    simulator(repo, states, rabbit).advanceShipments();

    assertEquals("PICKED_UP", shipment.status);
    assertEquals("Origin warehouse", shipment.tracking.get(0).location);
    assertEquals("Carrier picked up the parcel", shipment.tracking.get(0).detail);
    verify(states).save(argThat(state -> "PICKED_UP".equals(state.status)));
    verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  @Test
  void recordsDeliveryAndPublishesOrderEventWhenOutForDelivery() {
    var repo = mock(ShipmentRepository.class);
    var states = mock(ShipmentStateRepository.class);
    var rabbit = mock(RabbitTemplate.class);
    var shipment = shipment("OUT_FOR_DELIVERY");
    when(repo.findByStatusNot("DELIVERED")).thenReturn(List.of(shipment));
    when(repo.findById("shipment-1")).thenReturn(java.util.Optional.of(shipment));
    when(repo.findAll()).thenReturn(List.of(shipment));

    simulator(repo, states, rabbit).advanceShipments();

    assertEquals("DELIVERED", shipment.status);
    assertEquals("Customer address", shipment.tracking.get(0).location);
    assertEquals(true, shipment.deliveryEventPublished);
    verify(rabbit).convertAndSend("logistics.events", "ShipmentDelivered",
        Map.of("eventType", "ShipmentDelivered", "shipmentId", "shipment-1", "orderId", "order-1"));
  }

  @Test
  void ignoresDuplicateInventoryReservationEvents() {
    var repo = mock(ShipmentRepository.class);
    var states = mock(ShipmentStateRepository.class);
    var rabbit = mock(RabbitTemplate.class);
    when(repo.findByOrderId("order-1")).thenReturn(List.of(shipment("CREATED")));

    new InventoryReservedConsumer(repo, states, rabbit).onReserved(Map.of("orderId", "order-1"));

    verify(repo, never()).save(any(Shipment.class));
    verifyNoInteractions(states, rabbit);
  }

  private static ShipmentSimulator simulator(
      ShipmentRepository repo, ShipmentStateRepository states, RabbitTemplate rabbit) {
    var controller = new ShipmentController(repo, states, rabbit);
    return new ShipmentSimulator(repo, states, controller, true);
  }

  private static Shipment shipment(String status) {
    var shipment = new Shipment();
    shipment.id = "shipment-1";
    shipment.orderId = "order-1";
    shipment.status = status;
    return shipment;
  }
}
