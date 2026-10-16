# PH-1 — Domínio de pagamentos e caso de uso `ReceivePayment` (sem Spring)

| Campo | Valor |
|---|---|
| Tipo | Story |
| Épico | PH — Hub de integração de pagamentos |
| Prioridade | Alta |
| Estimativa | 4 a 6 horas |
| Pré-requisito | Build verde com Java 21 + Spring Boot 4.1 (feito) |
| Labels | `domain` `hexagonal` `tdd` `java21` |

---

## 1. Contexto

O hub recebe eventos de pagamento de provedores diferentes (webhook, CSV via SFTP, fila). Antes de falar de HTTP, banco ou Camel, precisamos do **coração do sistema**: as regras de negócio escritas em Java puro, testáveis em milissegundos, sem subir nenhum framework.

Tudo que vier depois (controller REST, rotas Camel, JPA, RabbitMQ) será só **adaptador** ligado às portas que criamos aqui.

## 2. Objetivo

Existir um caso de uso `ReceivePaymentUseCase` que:

1. recebe um comando neutro (já traduzido do formato do provedor);
2. ignora duplicados (idempotência por `provider` + `externalId`);
3. cria o `Payment`, aplica a regra de revisão por valor e decide o status;
4. salva pelo repositório (porta de saída);
5. publica um evento (porta de saída);
6. devolve um resultado.

Tudo coberto por testes unitários que usam **fakes em memória**, sem Spring.

## 3. Fora de escopo (próximas stories)

- Banco, Flyway, JPA, MapStruct (PH-2)
- Controller REST (PH-3)
- Rotas Camel e RabbitMQ (PH-4 em diante)
- Segurança, observabilidade

## 4. Decisões de design já tomadas

| Decisão | Motivo |
|---|---|
| `Money` = `BigDecimal` + `Currency`, como `record` | `double` erra centavos. O record garante imutabilidade e `equals` por valor |
| Status: `RECEIVED → CONFIRMED / FAILED / UNDER_REVIEW` | `UNDER_REVIEW` ainda pode virar `CONFIRMED` ou `FAILED` |
| Getters escritos à mão no domínio | Você vê o Java de verdade. Lombok entra nos adaptadores |
| `Clock` injetado, nunca `Instant.now()` solto | Permite testar com data fixa |
| `ReceivePaymentService` **sem** `@Service` | O core não conhece Spring. A ligação (`@Configuration`) fica num adaptador na PH-3 |
| Limite de revisão: acima de 10.000,00 BRL | Regra de exemplo. Moeda diferente do limite vai para revisão (decisão conservadora) |

## 5. Critérios de aceite

```gherkin
Cenário: Pagamento normal é confirmado
  Dado um comando de 150.00 BRL do provider-a com externalId "evt-1"
  Quando o caso de uso é executado
  Então o status é CONFIRMED, duplicate é false, 1 pagamento salvo e 1 evento publicado

Cenário: Valor acima do limite vai para revisão
  Dado um comando de 10000.01 BRL
  Então o status é UNDER_REVIEW

Cenário: Valor igual ao limite é confirmado
  Dado um comando de 10000.00 BRL
  Então o status é CONFIRMED

Cenário: Evento repetido é idempotente
  Dado que (provider-a, evt-1) já foi processado
  Quando o mesmo comando chega de novo
  Então o resultado traz o mesmo paymentId e duplicate é true
  E nada novo é salvo nem publicado

Cenário: Valores inválidos são rejeitados
  Então valor 0 ou negativo lança InvalidPaymentException
  E moeda inexistente ou casas decimais demais lançam InvalidMoneyException

Cenário: Transição inválida é rejeitada
  Então confirmar um pagamento já CONFIRMED lança IllegalPaymentTransitionException

Cenário: O core é puro
  Então nenhuma classe em domain ou application importa Spring, Jakarta ou Camel
```

## 6. Mapa: como tudo se conecta

```
 Quem chama (teste hoje; controller REST e rotas Camel depois)
        │  ReceivePaymentCommand
        ▼
 ┌──────────────── application ────────────────────────────┐
 │  ReceivePaymentUseCase   (porta de entrada, interface)  │
 │          ▲ implementa                                   │
 │  ReceivePaymentService ──usa──► PaymentRepository       │ porta de saída
 │          │              └────► PaymentEventPublisher    │ porta de saída
 └──────────┼──────────────────────────────────────────────┘
            │ usa
            ▼
 ┌──────────────── domain ─────────────────────────────────┐
 │  Payment ── Money ── PaymentStatus ── PaymentId         │
 │  PaymentReviewPolicy     PaymentProcessedEvent          │
 │  DomainException ◄── InvalidMoney / InvalidPayment /    │
 │                      IllegalPaymentTransition           │
 └─────────────────────────────────────────────────────────┘

 Nos testes, as portas de saída são implementadas por fakes
 (InMemoryPaymentRepository, RecordingPaymentEventPublisher).
 Na PH-2 e PH-4 serão implementadas por JPA e Camel/RabbitMQ.
```

**Regra de ouro:** as setas de dependência apontam para dentro. `domain` não conhece ninguém. `application` conhece só `domain`. Quem implementa as portas de saída (fakes, JPA, Camel) conhece `application`.

### Fluxo do `execute()`

| # | Passo | Quem faz |
|---|---|---|
| 1 | Procura pagamento existente por `(provider, externalId)` | porta `PaymentRepository` |
| 2 | Se existe, devolve o mesmo resultado com `duplicate = true` e para | `ReceivePaymentService` |
| 3 | Converte valor e moeda do comando em `Money` | `Money.of` |
| 4 | Cria o `Payment` em `RECEIVED` | `Payment.receive` |
| 5 | Pergunta à política se precisa de revisão | `PaymentReviewPolicy` |
| 6 | Move para `UNDER_REVIEW` ou `CONFIRMED` | `Payment` valida a transição |
| 7 | Salva | porta `PaymentRepository` |
| 8 | Publica o evento | porta `PaymentEventPublisher` |
| 9 | Devolve `ReceivePaymentResult` | `ReceivePaymentService` |

---

## 7. Subtasks

> Como estudar: **digite o código você mesmo**, não cole. Depois de cada subtask rode `mvn compile` (ou `mvn test` quando houver teste). Ler a mensagem de erro do compilador faz parte do aprendizado.

### PH-1.1 — Criar a estrutura de pacotes

Dentro de `src/main/java/com/v1/paymenthub/`:

```
domain/
  model/        Money, PaymentId, PaymentStatus, Payment
  policy/       PaymentReviewPolicy
  event/        PaymentProcessedEvent
  exception/    DomainException e filhas
application/
  port/in/      ReceivePaymentUseCase, ReceivePaymentCommand, ReceivePaymentResult
  port/out/     PaymentRepository, PaymentEventPublisher
  service/      ReceivePaymentService
```

Em `src/test/java/com/v1/paymenthub/` espelhe os mesmos pacotes, mais um pacote `support/` para os fakes.

**Por que pacotes e não módulos Maven?** Para estudo, pacotes bastam. A regra de dependência será garantida por teste (PH-1.11).

---

### PH-1.2 — Exceções de domínio

Erros de regra de negócio têm tipo próprio. Assim, mais tarde, o adaptador web pode traduzir cada um em HTTP 400 ou 422 sem adivinhar.

<!-- file: src/main/java/com/v1/paymenthub/domain/exception/DomainException.java -->
```java
package com.v1.paymenthub.domain.exception;

public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
```

<!-- file: src/main/java/com/v1/paymenthub/domain/exception/InvalidMoneyException.java -->
```java
package com.v1.paymenthub.domain.exception;

public class InvalidMoneyException extends DomainException {

    public InvalidMoneyException(String message) {
        super(message);
    }
}
```

<!-- file: src/main/java/com/v1/paymenthub/domain/exception/InvalidPaymentException.java -->
```java
package com.v1.paymenthub.domain.exception;

public class InvalidPaymentException extends DomainException {

    public InvalidPaymentException(String message) {
        super(message);
    }
}
```

<!-- file: src/main/java/com/v1/paymenthub/domain/exception/IllegalPaymentTransitionException.java -->
```java
package com.v1.paymenthub.domain.exception;

public class IllegalPaymentTransitionException extends DomainException {

    public IllegalPaymentTransitionException(String message) {
        super(message);
    }
}
```

**O que está acontecendo**

- `RuntimeException` é uma exceção **não checada**: o compilador não obriga `throws` nem `try/catch`. É o padrão no ecossistema Spring.
- `abstract` impede `new DomainException(...)`. Só as filhas existem de verdade.
- `protected` no construtor da base: só filhas (e o próprio pacote) chamam.
- `super(message)` repassa a mensagem para `RuntimeException`.

---

### PH-1.3 — `PaymentId`

<!-- file: src/main/java/com/v1/paymenthub/domain/model/PaymentId.java -->
```java
package com.v1.paymenthub.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "value is required");
    }

    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
```

**O que está acontecendo**

- **`record`** (Java 16+): classe imutável de dados. O compilador gera construtor, getters (`value()`), `equals`, `hashCode` e `toString`. Dois `PaymentId` com o mesmo UUID são iguais.
- **Construtor compacto** (`public PaymentId { ... }`): roda antes de atribuir os campos. É o lugar certo para validar.
- **Por que embrulhar um `UUID` em outro tipo?** Evita trocar por engano um `PaymentId` por outro UUID qualquer. O compilador passa a ajudar você. Isso se chama *value object*.
- **Método estático de fábrica** (`newId()`): nome descritivo, melhor que `new PaymentId(UUID.randomUUID())` espalhado pelo código.

---

### PH-1.4 — `Money`

<!-- file: src/main/java/com/v1/paymenthub/domain/model/Money.java -->
```java
package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.InvalidMoneyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");

        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new InvalidMoneyException("Currency not supported: " + currency.getCurrencyCode());
        }
        if (amount.stripTrailingZeros().scale() > fractionDigits) {
            throw new InvalidMoneyException(
                    "Amount %s has more decimal places than %s allows (%d)"
                            .formatted(amount.toPlainString(), currency.getCurrencyCode(), fractionDigits));
        }
        amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode is required");
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidMoneyException("Unknown currency code: " + currencyCode);
        }
        return new Money(amount, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new InvalidMoneyException(
                    "Currency mismatch: %s vs %s".formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
```

**O que está acontecendo**

- **`BigDecimal`**: números decimais exatos. `0.1 + 0.2` em `double` dá `0.30000000000000004`. Em dinheiro isso é bug.
- **`scale()`** é o número de casas decimais. `stripTrailingZeros()` tira zeros à direita, então `10.50` tem scale efetivo 1. Se passar do limite da moeda (2 para BRL), rejeitamos: **nunca arredondamos dinheiro em silêncio**.
- **`setScale(2, UNNECESSARY)`**: normaliza `10` para `10.00`. `UNNECESSARY` é uma trava: se algum arredondamento fosse necessário, lançaria exceção (não vai acontecer, pois já validamos acima).
- **Reatribuir `amount` dentro do construtor compacto** é permitido e é o jeito de normalizar um campo do record.
- **`compareTo` e não `equals`**: no `BigDecimal`, `equals` considera a escala (`2.0` ≠ `2.00`), `compareTo` compara só o valor. Para ordem e comparação numérica, sempre `compareTo`.
- **`formatted(...)`**: é `String.format` como método da própria String (Java 15+).
- **`Currency`** é da JDK e conhece as casas decimais de cada moeda (BRL = 2, JPY = 0).
- **try/catch só em volta do que pode falhar** (`Currency.getInstance`): assim não engolimos exceções de outras linhas.

---

### PH-1.5 — `PaymentStatus` com regras de transição

<!-- file: src/main/java/com/v1/paymenthub/domain/model/PaymentStatus.java -->
```java
package com.v1.paymenthub.domain.model;

public enum PaymentStatus {
    RECEIVED,
    UNDER_REVIEW,
    CONFIRMED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case RECEIVED -> target == UNDER_REVIEW || target == CONFIRMED || target == FAILED;
            case UNDER_REVIEW -> target == CONFIRMED || target == FAILED;
            case CONFIRMED, FAILED -> false;
        };
    }
}
```

**O que está acontecendo**

- **`enum` em Java é uma classe**: pode ter métodos. A regra "quem pode ir para onde" mora junto do status, não espalhada em `if`.
- **`switch` como expressão** (Java 14+): devolve valor, usa `->` e não precisa de `break`. Em enum, o compilador exige cobrir **todos** os casos. Se alguém adicionar um status novo, o código para de compilar até a regra ser decidida. É uma rede de segurança.
- **`case CONFIRMED, FAILED -> false`**: estados finais, de onde não se sai.

---

### PH-1.6 — `Payment` (a entidade)

<!-- file: src/main/java/com/v1/paymenthub/domain/model/Payment.java -->
```java
package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.IllegalPaymentTransitionException;
import com.v1.paymenthub.domain.exception.InvalidPaymentException;

import java.time.Instant;
import java.util.Objects;

public final class Payment {

    private final PaymentId id;
    private final String provider;
    private final String externalId;
    private final Money money;
    private final Instant receivedAt;
    private PaymentStatus status;
    private Instant updatedAt;

    private Payment(PaymentId id, String provider, String externalId, Money money,
                    PaymentStatus status, Instant receivedAt, Instant updatedAt) {
        this.id = id;
        this.provider = provider;
        this.externalId = externalId;
        this.money = money;
        this.status = status;
        this.receivedAt = receivedAt;
        this.updatedAt = updatedAt;
    }

    public static Payment receive(String provider, String externalId, Money money, Instant now) {
        Objects.requireNonNull(money, "money is required");
        Objects.requireNonNull(now, "now is required");
        if (!money.isPositive()) {
            throw new InvalidPaymentException("Payment amount must be positive");
        }
        return new Payment(
                PaymentId.newId(),
                requireText(provider, "provider"),
                requireText(externalId, "externalId"),
                money,
                PaymentStatus.RECEIVED,
                now,
                now);
    }

    public static Payment restore(PaymentId id, String provider, String externalId, Money money,
                                  PaymentStatus status, Instant receivedAt, Instant updatedAt) {
        return new Payment(
                Objects.requireNonNull(id, "id is required"),
                requireText(provider, "provider"),
                requireText(externalId, "externalId"),
                Objects.requireNonNull(money, "money is required"),
                Objects.requireNonNull(status, "status is required"),
                Objects.requireNonNull(receivedAt, "receivedAt is required"),
                Objects.requireNonNull(updatedAt, "updatedAt is required"));
    }

    public void sendToReview(Instant now) {
        transitionTo(PaymentStatus.UNDER_REVIEW, now);
    }

    public void confirm(Instant now) {
        transitionTo(PaymentStatus.CONFIRMED, now);
    }

    public void fail(Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
    }

    private void transitionTo(PaymentStatus target, Instant now) {
        Objects.requireNonNull(now, "now is required");
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(
                    "Payment %s cannot go from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPaymentException(field + " is required");
        }
        return value;
    }

    public PaymentId getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public Money getMoney() {
        return money;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Payment other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

**O que está acontecendo**

- **Entidade ≠ value object**: `Money` é igual a outro `Money` com o mesmo valor. `Payment` é "o mesmo" pelo **id**, mesmo que o status mude. Por isso `equals`/`hashCode` olham só o `id`.
- **Construtor `private` + fábricas estáticas**: ninguém consegue criar um `Payment` inválido. `receive` é o nascimento (sempre `RECEIVED`, valida tudo). `restore` reidrata do banco (será usada na PH-2).
- **Campos `final` vs. mutáveis**: o que nunca muda é `final`. `status` e `updatedAt` mudam, mas **só por dentro**, via `transitionTo`. Não existe `setStatus` público, então ninguém pula a regra.
- **Tell, don't ask**: o chamador não faz `if (status == RECEIVED) payment.setStatus(CONFIRMED)`. Ele pede `payment.confirm(now)` e o objeto decide.
- **`final class`**: ninguém herda e quebra as regras.
- **`o instanceof Payment other && ...`**: *pattern matching* (Java 16+), faz o teste de tipo e a conversão numa linha.
- **Getters com prefixo `get`**: o MapStruct, que vem na PH-2, espera esse padrão em classes comuns.

---

### PH-1.7 — Política de revisão e evento

<!-- file: src/main/java/com/v1/paymenthub/domain/policy/PaymentReviewPolicy.java -->
```java
package com.v1.paymenthub.domain.policy;

import com.v1.paymenthub.domain.model.Money;

import java.util.Objects;

public class PaymentReviewPolicy {

    private final Money threshold;

    public PaymentReviewPolicy(Money threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold is required");
    }

    public boolean requiresReview(Money amount) {
        if (!amount.currency().equals(threshold.currency())) {
            return true;
        }
        return amount.isGreaterThan(threshold);
    }
}
```

<!-- file: src/main/java/com/v1/paymenthub/domain/event/PaymentProcessedEvent.java -->

```java
package com.v1.paymenthub.domain.event;

import com.v1.paymenthub.domain.model.Money;
import com.v1.paymenthub.domain.model.Payment;
import com.v1.paymenthub.domain.model.PaymentStatus;

import java.time.Instant;

public record PaymentProcessedEvent(
        PaymentId paymentId,
        String provider,
        String externalId,
        Money money,
        PaymentStatus status,
        Instant occurredAt) {

    public static PaymentProcessedEvent from(Payment payment) {
        return new PaymentProcessedEvent(
                payment.getId(),
                payment.getProvider(),
                payment.getExternalId(),
                payment.getMoney(),
                payment.getStatus(),
                payment.getUpdatedAt());
    }
}
```

**O que está acontecendo**

- **A política isola uma regra que muda com frequência** (o limite). O serviço só pergunta `requiresReview(...)`.
- **Estritamente maior**: valor igual ao limite é confirmado (critério de aceite).
- **Evento = fato que já aconteceu**, por isso o nome no passado e o `record` imutável. Na PH-4 o Camel vai transformá-lo em mensagem para o RabbitMQ.
- O evento carrega **cópias dos dados** (e não o `Payment` inteiro), para quem consome não depender da entidade.

---

### PH-1.8 — Portas (contratos)

<!-- file: src/main/java/com/v1/paymenthub/application/port/in/ReceivePaymentCommand.java -->
```java
package com.v1.paymenthub.application.port.in;

import java.math.BigDecimal;

public record ReceivePaymentCommand(
        String provider,
        String externalId,
        BigDecimal amount,
        String currency) {
}
```

<!-- file: src/main/java/com/v1/paymenthub/application/port/in/ReceivePaymentResult.java -->

```java
package com.v1.paymenthub.application.port.in;

import com.v1.paymenthub.domain.model.PaymentStatus;

public record ReceivePaymentResult(
        PaymentId paymentId,
        PaymentStatus status,
        boolean duplicate) {
}
```

<!-- file: src/main/java/com/v1/paymenthub/application/port/in/ReceivePaymentUseCase.java -->
```java
package com.v1.paymenthub.application.port.in;

public interface ReceivePaymentUseCase {

    ReceivePaymentResult execute(ReceivePaymentCommand command);
}
```

<!-- file: src/main/java/com/v1/paymenthub/application/port/out/PaymentRepository.java -->
```java
package com.v1.paymenthub.application.port.out;

import com.v1.paymenthub.domain.model.Payment;

import java.util.Optional;

public interface PaymentRepository {

    Optional<Payment> findByProviderAndExternalId(String provider, String externalId);

    void save(Payment payment);
}
```

<!-- file: src/main/java/com/v1/paymenthub/application/port/out/PaymentEventPublisher.java -->
```java
package com.v1.paymenthub.application.port.out;

import com.v1.paymenthub.domain.event.PaymentProcessedEvent;

public interface PaymentEventPublisher {

    void publish(PaymentProcessedEvent event);
}
```

**O que está acontecendo**

- **Porta de entrada** (`port.in`): o que o mundo pode pedir ao sistema. O controller e a rota Camel vão chamar `ReceivePaymentUseCase`, nunca o serviço direto.
- **Porta de saída** (`port.out`): o que o sistema precisa do mundo. Ele declara a necessidade ("preciso salvar e achar pagamentos") sem saber se é Postgres, memória ou outra coisa.
- **O comando usa tipos simples** (`String`, `BigDecimal`), porque vem de fora. É o serviço que o transforma em `Money` e valida. Quem monta o comando (adaptador) não precisa conhecer o domínio.
- **`Optional<Payment>`**: o contrato diz "pode não existir", em vez de devolver `null` e esperar um `NullPointerException` depois.
- **A interface é mantida pelo core**: o adaptador JPA é que se adapta a ela (inversão de dependência).

---

### PH-1.9 — `ReceivePaymentService`

<!-- file: src/main/java/com/v1/paymenthub/application/service/ReceivePaymentService.java -->
```java
package com.v1.paymenthub.application.service;

import com.v1.paymenthub.application.port.in.ReceivePaymentCommand;
import com.v1.paymenthub.application.port.in.ReceivePaymentResult;
import com.v1.paymenthub.application.port.in.ReceivePaymentUseCase;
import com.v1.paymenthub.application.port.out.PaymentEventPublisher;
import com.v1.paymenthub.application.port.out.PaymentRepository;
import com.v1.paymenthub.domain.event.PaymentProcessedEvent;
import com.v1.paymenthub.domain.model.Money;
import com.v1.paymenthub.domain.model.Payment;
import com.v1.paymenthub.domain.policy.PaymentReviewPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class ReceivePaymentService implements ReceivePaymentUseCase {

    private final PaymentRepository repository;
    private final PaymentEventPublisher publisher;
    private final PaymentReviewPolicy reviewPolicy;
    private final Clock clock;

    public ReceivePaymentService(PaymentRepository repository,
                                 PaymentEventPublisher publisher,
                                 PaymentReviewPolicy reviewPolicy,
                                 Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.publisher = Objects.requireNonNull(publisher, "publisher is required");
        this.reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public ReceivePaymentResult execute(ReceivePaymentCommand command) {
        Optional<Payment> existing =
                repository.findByProviderAndExternalId(command.provider(), command.externalId());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            return new ReceivePaymentResult(payment.getId(), payment.getStatus(), true);
        }

        Money money = Money.of(command.amount(), command.currency());
        Instant now = clock.instant();

        Payment payment = Payment.receive(command.provider(), command.externalId(), money, now);
        if (reviewPolicy.requiresReview(money)) {
            payment.sendToReview(now);
        } else {
            payment.confirm(now);
        }

        repository.save(payment);
        publisher.publish(PaymentProcessedEvent.from(payment));

        return new ReceivePaymentResult(payment.getId(), payment.getStatus(), false);
    }
}
```

**O que está acontecendo**

- **`implements ReceivePaymentUseCase`**: o serviço cumpre o contrato da porta de entrada.
- **Injeção por construtor**: o serviço recebe tudo de que precisa (`repository`, `publisher`, `policy`, `clock`) e guarda em campos `final`. É a mesma ideia que o Spring faz por você com `@Autowired`, só que aqui você vê o mecanismo. Nos testes você passa fakes, em produção o Spring passará as implementações reais.
- **Nenhuma anotação Spring**: o core não sabe que Spring existe.
- **Idempotência**: antes de criar algo, o serviço procura. Se achar, devolve o resultado anterior com `duplicate = true`. Isso protege contra o provedor reenviar o mesmo webhook.
- **Orquestra, não decide**: o serviço sequencia os passos. As regras estão em `Payment` (transições), `PaymentReviewPolicy` (limite) e `Money` (valores).
- **`Clock`**: `clock.instant()` em vez de `Instant.now()`. No teste usamos `Clock.fixed(...)`, e o tempo fica previsível.
- **Limitação conhecida**: se dois eventos iguais chegarem **ao mesmo tempo**, ambos podem passar pelo `find` antes do `save`. Na PH-2 o `UNIQUE (provider, external_id)` no banco fecha essa brecha.

---

### PH-1.10 — Testes com fakes

Primeiro, os fakes. São as implementações "de mentira" das portas de saída.

<!-- file: src/test/java/com/v1/paymenthub/support/InMemoryPaymentRepository.java -->
```java
package com.v1.paymenthub.support;

import com.v1.paymenthub.application.port.out.PaymentRepository;
import com.v1.paymenthub.domain.model.Payment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, Payment> store = new HashMap<>();

    @Override
    public Optional<Payment> findByProviderAndExternalId(String provider, String externalId) {
        return Optional.ofNullable(store.get(key(provider, externalId)));
    }

    @Override
    public void save(Payment payment) {
        store.put(key(payment.getProvider(), payment.getExternalId()), payment);
    }

    public int count() {
        return store.size();
    }

    private static String key(String provider, String externalId) {
        return provider + "::" + externalId;
    }
}
```

<!-- file: src/test/java/com/v1/paymenthub/support/RecordingPaymentEventPublisher.java -->
```java
package com.v1.paymenthub.support;

import com.v1.paymenthub.application.port.out.PaymentEventPublisher;
import com.v1.paymenthub.domain.event.PaymentProcessedEvent;

import java.util.ArrayList;
import java.util.List;

public class RecordingPaymentEventPublisher implements PaymentEventPublisher {

    private final List<PaymentProcessedEvent> events = new ArrayList<>();

    @Override
    public void publish(PaymentProcessedEvent event) {
        events.add(event);
    }

    public List<PaymentProcessedEvent> events() {
        return List.copyOf(events);
    }
}
```

Agora os testes. Dois exemplos completos de domínio e um do serviço. **Os demais cenários do critério de aceite ficam como exercício seu.**

<!-- file: src/test/java/com/v1/paymenthub/domain/model/MoneyTest.java -->
```java
package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.InvalidMoneyException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void shouldNormalizeScaleToTheCurrencyFractionDigits() {
        Money money = Money.of(new BigDecimal("10"), "BRL");

        assertThat(money.amount()).isEqualByComparingTo("10.00");
        assertThat(money.amount().scale()).isEqualTo(2);
    }

    @Test
    void shouldRejectMoreDecimalPlacesThanTheCurrencyAllows() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.005"), "BRL"))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void shouldRejectUnknownCurrencyCode() {
        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, "XYZ"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("XYZ");
    }

    @Test
    void shouldCompareAmountsInTheSameCurrency() {
        Money small = Money.of(new BigDecimal("10.00"), "BRL");
        Money big = Money.of(new BigDecimal("10.01"), "BRL");

        assertThat(big.isGreaterThan(small)).isTrue();
        assertThat(small.isGreaterThan(big)).isFalse();
        assertThat(small.isGreaterThan(small)).isFalse();
    }
}
```

<!-- file: src/test/java/com/v1/paymenthub/domain/model/PaymentTest.java -->
```java
package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.IllegalPaymentTransitionException;
import com.v1.paymenthub.domain.exception.InvalidPaymentException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private final Money tenReais = Money.of(new BigDecimal("10.00"), "BRL");

    @Test
    void shouldStartAsReceived() {
        Payment payment = Payment.receive("provider-a", "evt-1", tenReais, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RECEIVED);
        assertThat(payment.getReceivedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectZeroAmount() {
        Money zero = Money.of(BigDecimal.ZERO, "BRL");

        assertThatThrownBy(() -> Payment.receive("provider-a", "evt-1", zero, NOW))
                .isInstanceOf(InvalidPaymentException.class);
    }

    @Test
    void shouldNotConfirmTwice() {
        Payment payment = Payment.receive("provider-a", "evt-1", tenReais, NOW);
        payment.confirm(NOW);

        assertThatThrownBy(() -> payment.confirm(NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void shouldAllowConfirmingAfterReview() {
        Payment payment = Payment.receive("provider-a", "evt-1", tenReais, NOW);
        payment.sendToReview(NOW);
        payment.confirm(NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    }
}
```

<!-- file: src/test/java/com/v1/paymenthub/application/service/ReceivePaymentServiceTest.java -->
```java
package com.v1.paymenthub.application.service;

import com.v1.paymenthub.application.port.in.ReceivePaymentCommand;
import com.v1.paymenthub.application.port.in.ReceivePaymentResult;
import com.v1.paymenthub.domain.model.Money;
import com.v1.paymenthub.domain.model.PaymentStatus;
import com.v1.paymenthub.domain.policy.PaymentReviewPolicy;
import com.v1.paymenthub.support.InMemoryPaymentRepository;
import com.v1.paymenthub.support.RecordingPaymentEventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivePaymentServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    private final RecordingPaymentEventPublisher publisher = new RecordingPaymentEventPublisher();
    private final PaymentReviewPolicy policy =
            new PaymentReviewPolicy(Money.of(new BigDecimal("10000.00"), "BRL"));
    private final ReceivePaymentService service =
            new ReceivePaymentService(repository, publisher, policy, CLOCK);

    @Test
    void shouldConfirmPaymentBelowTheReviewThreshold() {
        ReceivePaymentResult result = service.execute(command("provider-a", "evt-1", "150.00"));

        assertThat(result.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(result.duplicate()).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(publisher.events()).hasSize(1);
        assertThat(publisher.events().get(0).status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void shouldBeIdempotentForTheSameProviderAndExternalId() {
        ReceivePaymentResult first = service.execute(command("provider-a", "evt-1", "150.00"));
        ReceivePaymentResult second = service.execute(command("provider-a", "evt-1", "150.00"));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(publisher.events()).hasSize(1);
    }

    private ReceivePaymentCommand command(String provider, String externalId, String amount) {
        return new ReceivePaymentCommand(provider, externalId, new BigDecimal(amount), "BRL");
    }
}
```

**Exercícios (escreva você mesmo no `ReceivePaymentServiceTest`)**

1. `shouldSendToReviewAboveTheThreshold`: `10000.01` resulta em `UNDER_REVIEW`.
2. `shouldConfirmWhenAmountEqualsTheThreshold`: `10000.00` resulta em `CONFIRMED` (teste de fronteira).
3. `shouldTreatSameExternalIdFromDifferentProvidersAsDifferentPayments`: `provider-a` e `provider-b` com o mesmo `evt-1` geram 2 pagamentos.
4. `shouldRejectInvalidCurrency`: moeda `"XYZ"` lança `InvalidMoneyException` e **não** salva nada nem publica evento.
5. `shouldPublishEventWithPaymentData`: confira `provider`, `externalId` e `occurredAt` do evento.

**O que está acontecendo**

- **Fake vs. mock**: o fake é uma implementação simples e real (um `HashMap`). Não precisamos de Mockito para testar comportamento. Teste lê melhor e quebra menos quando você refatora.
- **Teste de unidade verdadeiro**: sem `@SpringBootTest`, sem container. Rodam em milissegundos.
- **Padrão Arrange / Act / Assert**: monta o cenário (campos da classe e `command(...)`), executa (`service.execute`), verifica (`assertThat`).
- **JUnit 5**: `@Test` marca o teste. A classe e os métodos podem ser *package-private* (sem `public`).
- **AssertJ**: `assertThat(x).isEqualTo(...)` lê como frase e dá mensagens de erro boas.
- **Se `org.assertj` ou `org.junit` não resolverem na IDE**: adicione ao `pom.xml` a dependência `spring-boot-starter-test` com `<scope>test</scope>`. O Spring Boot gerencia a versão.

Rode: `mvn test`

---

### PH-1.11 — (Opcional) Teste de arquitetura com ArchUnit

Garante, por teste, que o domínio não importa framework. Adicione ao `pom.xml` a dependência `com.tngtech.archunit:archunit-junit5` com escopo `test` (confira a versão atual no Maven Central) e crie:

```java
package com.v1.paymenthub;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.v1.paymenthub")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPure =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..", "..adapter..",
                            "org.springframework..", "jakarta..", "org.apache.camel..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapter..", "org.springframework..", "jakarta..", "org.apache.camel..");
}
```

Experimento: coloque temporariamente um `import org.springframework.stereotype.Service;` em `Payment` e veja o teste falhar. Depois remova.

---

## 8. Definition of Done

- [ ] `mvn test` verde
- [ ] Todos os critérios de aceite da seção 5 cobertos por teste
- [ ] Nenhum `import` de Spring, Jakarta ou Camel em `domain` e `application`
- [ ] Nenhum `setStatus` público e nenhum `double` para dinheiro
- [ ] Commit: `feat(domain): receive payment use case with in-memory ports`
- [ ] Você consegue explicar, sem olhar, o caminho de uma chamada a `execute()`

## 9. Glossário Java desta story

| Conceito | Onde apareceu |
|---|---|
| `record` e construtor compacto | `Money`, `PaymentId`, `ReceivePaymentCommand` |
| `enum` com método e `switch` como expressão | `PaymentStatus` |
| Construtor privado + método estático de fábrica | `Payment.receive`, `Money.of` |
| Imutabilidade (`final`) | quase todos os campos |
| `interface` como contrato | portas de entrada e de saída |
| Injeção de dependência por construtor | `ReceivePaymentService` |
| `Optional` | `PaymentRepository.findBy...` |
| `Objects.requireNonNull` | fail-fast nos construtores |
| `BigDecimal`, `scale`, `compareTo` | `Money` |
| `java.time.Clock` e `Instant` | serviço e testes |
| Pattern matching com `instanceof` | `Payment.equals` |
| Exceções não checadas e hierarquia | `DomainException` e filhas |

## 10. Armadilhas comuns

1. **`new BigDecimal(0.1)`**: usa o `double` impreciso. Use `new BigDecimal("0.1")` com `String`.
2. **`equals` em `BigDecimal`**: `2.0` e `2.00` são diferentes. Compare com `compareTo`.
3. **Colocar `@Service` no serviço** "para facilitar": quebra o isolamento do core.
4. **Receber `Payment` ou entidade JPA no comando**: o comando deve ser só dados simples.
5. **Lógica de negócio dentro de rota Camel ou controller**: mora no domínio ou no serviço.
6. **`Instant.now()` espalhado**: teste fica dependente do relógio.

## 11. Próxima story

**PH-2 — Persistência:** migration `V1__create_payments.sql` com `UNIQUE (provider, external_id)`, `PaymentEntity`, mapper MapStruct, `JpaPaymentRepositoryAdapter` implementando `PaymentRepository`, e teste de integração com Testcontainers.
