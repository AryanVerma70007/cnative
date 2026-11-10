package com.logistics.shipment;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class Tracking {
  public Instant at = Instant.now();
  public String status;
  public String location;
  public String detail;

  Tracking() {}

  Tracking(String status, String location, String detail) {
    this.status = status;
    this.location = location;
    this.detail = detail;
  }
}

@Entity
class ShipmentState {
  @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
  String orderId;
  String status;

  ShipmentState() {}

  ShipmentState(String orderId, String status) {
    this.orderId = orderId;
    this.status = status;
  }

  public UUID getId() { return id; }
  public String getOrderId() { return orderId; }
  public String getStatus() { return status; }
}

interface ShipmentStateRepository extends JpaRepository<ShipmentState, UUID> {}

@Document("shipments")
class Shipment {
  @org.springframework.data.annotation.Id public String id;
  public String orderId;
  public String status = "CREATED";
  public List<Tracking> tracking = new ArrayList<>();
  public Boolean deliveryEventPublished;
}

interface ShipmentRepository extends MongoRepository<Shipment, String> {
  List<Shipment> findByOrderId(String orderId);
  List<Shipment> findByStatusNot(String status);
}

@RestController
@RequestMapping("/api/shipments")
class ShipmentController {
  private final ShipmentRepository repo;
  private final ShipmentStateRepository states;
  private final RabbitTemplate rabbit;

  ShipmentController(ShipmentRepository repo, ShipmentStateRepository states, RabbitTemplate rabbit) {
    this.repo = repo;
    this.states = states;
    this.rabbit = rabbit;
  }

  @GetMapping
  List<Shipment> all() {
    return repo.findAll();
  }

  @GetMapping("/{id}")
  Optional<Shipment> one(@PathVariable String id) {
    return repo.findById(id);
  }

  @PostMapping("/{id}/deliver")
  Shipment deliver(@PathVariable String id) {
    var shipment = repo.findById(id).orElseThrow();
    if (!"DELIVERED".equals(shipment.status)) {
      shipment.deliveryEventPublished = false;
      recordStage(shipment, "DELIVERED");
    } else if (shipment.deliveryEventPublished == null) {
      shipment.deliveryEventPublished = true;
      return repo.save(shipment);
    }
    publishDeliveryIfNeeded(shipment);
    return shipment;
  }

  private void recordStage(Shipment shipment, String status) {
    var tracking = trackingFor(status);
    shipment.status = status;
    shipment.tracking.add(tracking);
    states.save(new ShipmentState(shipment.orderId, status));
    repo.save(shipment);
  }

  private void publishDeliveryIfNeeded(Shipment shipment) {
    if (!"DELIVERED".equals(shipment.status) || Boolean.TRUE.equals(shipment.deliveryEventPublished)) {
      return;
    }
    rabbit.convertAndSend("logistics.events", "ShipmentDelivered", Map.of(
        "eventType", "ShipmentDelivered", "shipmentId", shipment.id, "orderId", shipment.orderId));
    shipment.deliveryEventPublished = true;
    repo.save(shipment);
  }

  static Tracking trackingFor(String status) {
    return switch (status) {
      case "CREATED" -> new Tracking(status, "Origin warehouse", "Shipment label created");
      case "PICKED_UP" -> new Tracking(status, "Origin warehouse", "Carrier picked up the parcel");
      case "IN_TRANSIT" -> new Tracking(status, "Regional sorting hub", "Parcel is moving between facilities");
      case "OUT_FOR_DELIVERY" -> new Tracking(status, "Destination city", "Courier is out for delivery");
      case "DELIVERED" -> new Tracking(status, "Customer address", "Parcel delivered successfully");
      default -> throw new IllegalArgumentException("Unsupported shipment status: " + status);
    };
  }
}

@Component
class InventoryReservedConsumer {
  private final ShipmentRepository repo;
  private final ShipmentStateRepository states;
  private final RabbitTemplate rabbit;

  InventoryReservedConsumer(ShipmentRepository repo, ShipmentStateRepository states, RabbitTemplate rabbit) {
    this.repo = repo;
    this.states = states;
    this.rabbit = rabbit;
  }

  @RabbitListener(queues = "shipment.inventory-reserved")
  void onReserved(Map<String, Object> event) {
    String orderId = String.valueOf(event.get("orderId"));
    var existing = repo.findByOrderId(orderId);
    if (!existing.isEmpty()) {
      return;
    }

    var shipment = new Shipment();
    shipment.orderId = orderId;
    shipment.deliveryEventPublished = false;
    shipment.tracking.add(ShipmentController.trackingFor("CREATED"));
    var saved = repo.save(shipment);
    states.save(new ShipmentState(shipment.orderId, shipment.status));
    rabbit.convertAndSend("logistics.events", "ShipmentCreated", Map.of(
        "eventType", "ShipmentCreated", "shipmentId", saved.id, "orderId", saved.orderId));
  }
}

@Component
class ShipmentSimulator {
  private static final Map<String, String> NEXT_STAGE = Map.of(
      "CREATED", "PICKED_UP",
      "PICKED_UP", "IN_TRANSIT",
      "IN_TRANSIT", "OUT_FOR_DELIVERY",
      "OUT_FOR_DELIVERY", "DELIVERED");

  private final ShipmentRepository repo;
  private final ShipmentStateRepository states;
  private final ShipmentController controller;
  private final boolean enabled;

  ShipmentSimulator(
      ShipmentRepository repo,
      ShipmentStateRepository states,
      ShipmentController controller,
      @org.springframework.beans.factory.annotation.Value("${shipment.simulation.enabled:true}") boolean enabled) {
    this.repo = repo;
    this.states = states;
    this.controller = controller;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${shipment.simulation.interval-ms:10000}")
  void advanceShipments() {
    if (!enabled) {
      return;
    }
    for (var shipment : repo.findByStatusNot("DELIVERED")) {
      String next = NEXT_STAGE.get(shipment.status);
      if (next == null) {
        continue;
      }
      if ("DELIVERED".equals(next)) {
        controller.deliver(shipment.id);
      } else {
        var tracking = ShipmentController.trackingFor(next);
        shipment.status = next;
        shipment.tracking.add(tracking);
        states.save(new ShipmentState(shipment.orderId, next));
        repo.save(shipment);
      }
    }
    for (var shipment : repo.findAll()) {
      if ("DELIVERED".equals(shipment.status) && !Boolean.TRUE.equals(shipment.deliveryEventPublished)) {
        controller.deliver(shipment.id);
      }
    }
  }
}
