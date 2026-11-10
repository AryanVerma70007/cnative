package com.logistics.order;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;

@Entity
class OrderRecord {
  @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
  String customerId;
  String customer;
  String sku;
  int quantity;
  int total;
  String status = "PENDING_INVENTORY";
  Instant createdAt = Instant.now();
  public UUID getId() { return id; }
  public String getCustomerId() { return customerId; }
  public String getCustomer() { return customer; }
  public String getSku() { return sku; }
  public int getQuantity() { return quantity; }
  public int getTotal() { return total; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}

interface OrderRepository extends JpaRepository<OrderRecord, UUID> {
  List<OrderRecord> findByCustomerId(String customerId);
}

@Entity
class IdempotencyKey {
  @Id String key;
  Instant createdAt = Instant.now();
  IdempotencyKey() {}
  IdempotencyKey(String key) { this.key = key; }
}

interface IdempotencyRepository extends JpaRepository<IdempotencyKey, String> {}

@Entity
class OutboxEvent {
  @Id String eventId;
  String eventType;
  String orderId;
  String customerId;
  String sku;
  int quantity;
  String status = "PENDING";
  int attempts;
  Instant nextAttemptAt = Instant.now();
  Instant createdAt = Instant.now();
  OutboxEvent() {}
  OutboxEvent(String eventId, String eventType, OrderRecord order) {
    this.eventId = eventId; this.eventType = eventType; this.orderId = order.id.toString();
    this.customerId = order.customerId; this.sku = order.sku; this.quantity = order.quantity;
  }
}
interface OutboxRepository extends JpaRepository<OutboxEvent, String> {
  List<OutboxEvent> findTop25ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status, Instant now);
}

@RestController
class OrderController {
  private final OrderRepository orders;
  private final IdempotencyRepository keys;
  private final OutboxRepository outbox;
  private final JwtTokenService tokens;
  private final PasswordEncoder passwordEncoder;

  OrderController(OrderRepository orders, IdempotencyRepository keys, OutboxRepository outbox,
                  JwtTokenService tokens, PasswordEncoder passwordEncoder) {
    this.orders = orders; this.keys = keys; this.outbox = outbox;
    this.tokens = tokens; this.passwordEncoder = passwordEncoder;
  }

  record Create(String customerId, String customer, String sku, int quantity, int total) {}

  @PostMapping("/api/orders")
  @Transactional
  ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @RequestBody Create body, Authentication authentication) {
    if (body.sku() == null || body.sku().isBlank() || body.quantity() < 1)
      return ResponseEntity.badRequest().body(Map.of("error", "sku and positive quantity are required"));
    if (key != null && keys.existsById(key))
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "duplicate request"));
    var order = new OrderRecord();
    boolean customer = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"));
    order.customerId = customer ? authentication.getName() : Objects.requireNonNullElse(body.customerId(), authentication.getName());
    order.customer = Objects.requireNonNullElse(body.customer(), "Customer");
    order.sku = body.sku(); order.quantity = body.quantity(); order.total = Math.max(body.total(), 0);
    orders.save(order);
    if (key != null) keys.save(new IdempotencyKey(key));
    outbox.save(new OutboxEvent(UUID.randomUUID().toString(), "OrderCreated", order));
    return ResponseEntity.status(HttpStatus.CREATED).body(order);
  }

  @GetMapping("/api/orders")
  List<OrderRecord> all(@RequestParam(required = false) String customerId, Authentication authentication) {
    boolean customer = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"));
    return customer ? orders.findByCustomerId(authentication.getName())
        : customerId == null ? orders.findAll() : orders.findByCustomerId(customerId);
  }

  @GetMapping("/api/orders/{id}")
  ResponseEntity<?> one(@PathVariable UUID id, Authentication authentication) {
    return orders.findById(id).filter(order -> !authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")) || order.customerId.equals(authentication.getName()))
        .<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
    String email = credentials.getOrDefault("email", "");
    String password = credentials.getOrDefault("password", "");
    Map<String, String> users = Map.of(
        "customer@logistics.local", passwordEncoder.encode("Customer123!"),
        "warehouse@logistics.local", passwordEncoder.encode("Warehouse123!"),
        "admin@logistics.local", passwordEncoder.encode("Admin123!"));
    if (!users.containsKey(email) || !passwordEncoder.matches(password, users.get(email)))
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("error", "Invalid email or password"));
    String role = email.startsWith("admin") ? "ADMIN" : email.startsWith("warehouse") ? "WAREHOUSE_OPERATOR" : "CUSTOMER";
    return ResponseEntity.ok(Map.of("token", tokens.create(email, role), "user", Map.of("id", role.equals("CUSTOMER") ? "u-customer" : role,
        "name", role.equals("CUSTOMER") ? "Neha Shah" : role, "email", email, "role", role)));
  }

  @GetMapping("/api/dashboard")
  Map<String, Object> dashboard() {
    return Map.of("stats", Map.of("totalOrders", orders.count(), "pendingOrders",
        orders.findAll().stream().filter(o -> o.status.contains("PENDING")).count()));
  }

}

@Component
class OutboxPublisher {
  private final OutboxRepository outbox;
  private final RabbitTemplate rabbit;
  OutboxPublisher(OutboxRepository outbox, RabbitTemplate rabbit) { this.outbox = outbox; this.rabbit = rabbit; }
  @Scheduled(fixedDelayString = "${outbox.publisher.delay-ms:1000}")
  @Transactional
  void publishPending() {
    for (var event : outbox.findTop25ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", Instant.now())) {
      try {
        rabbit.convertAndSend("logistics.events", event.eventType, Map.of(
            "eventId", event.eventId, "eventType", event.eventType, "orderId", event.orderId,
            "customerId", event.customerId, "sku", event.sku, "quantity", event.quantity));
        event.status = "PUBLISHED";
        outbox.save(event);
      } catch (RuntimeException error) {
        event.attempts++;
        if (event.attempts >= 5) event.status = "DEAD_LETTER";
        event.nextAttemptAt = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(event.attempts, 6)));
        outbox.save(event);
      }
    }
  }
}

@Component
class InventoryResultConsumer {
  private final OrderRepository orders;
  InventoryResultConsumer(OrderRepository orders) { this.orders = orders; }
  @RabbitListener(queues = "order.inventory-results")
  void onResult(Map<String, Object> event) {
    UUID id = UUID.fromString(String.valueOf(event.get("orderId")));
    orders.findById(id).ifPresent(order -> {
      order.status = "InventoryReserved".equals(event.get("eventType")) || event.containsKey("reserved")
          ? "READY_FOR_SHIPMENT" : "INVENTORY_REJECTED";
      orders.save(order);
    });
  }

  @Component
  class ShipmentDeliveredConsumer {
    private final OrderRepository orders;
    ShipmentDeliveredConsumer(OrderRepository orders) { this.orders = orders; }
    @RabbitListener(queues = "order.shipment-delivered")
    void onDelivered(Map<String, Object> event) {
      UUID id = UUID.fromString(String.valueOf(event.get("orderId")));
      orders.findById(id).ifPresent(order -> { order.status = "DELIVERED"; orders.save(order); });
    }
  }
}
