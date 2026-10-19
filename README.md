# Payment Hub

Hub de integração de pagamentos. Recebe eventos de pagamento de provedores distintos (webhook, CSV via SFTP, fila), normaliza para um formato neutro, aplica regras de negócio e publica o resultado para os demais sistemas.

## Sumário

- [Estado atual](#estado-atual)
- [Problema que resolve](#problema-que-resolve)
- [Regras de negócio](#regras-de-negócio)
- [Arquitetura](#arquitetura)
- [Contrato do caso de uso](#contrato-do-caso-de-uso)
- [Endpoints](#endpoints)
- [Stack](#stack)
- [Infraestrutura local](#infraestrutura-local)
- [Como executar](#como-executar)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Roadmap](#roadmap)

## Estado atual

O projeto está em desenvolvimento. O núcleo de domínio e as portas da aplicação estão implementados (story PH-1, em [docs](./docs/PH-1-dominio-pagamentos.md)). Ainda não existem adaptadores: controller REST, persistência JPA, rotas Camel e mensageria fazem parte das próximas etapas.

## Problema que resolve

Cada provedor de pagamento envia eventos em um formato e por um canal diferente, e os mesmos eventos podem chegar mais de uma vez. O hub isola essa variedade em adaptadores e mantém um único núcleo responsável por:

- evitar processar duas vezes o mesmo evento (idempotência);
- representar dinheiro sem erros de arredondamento;
- decidir automaticamente o que precisa de revisão manual;
- notificar o restante do ecossistema com um evento padronizado.

## Regras de negócio

| Regra                | Descrição                                                                                   |
| -------------------- | ------------------------------------------------------------------------------------------- |
| Idempotência         | A chave é `provider` + `externalId`. Um evento repetido devolve o mesmo `paymentId` com `duplicate = true`, sem salvar nem publicar nada novo. |
| Valor monetário      | `Money` combina `BigDecimal` e `Currency`. Valores com mais casas decimais que a moeda permite são rejeitados, nunca arredondados. |
| Revisão por valor    | Pagamentos acima de 10.000,00 BRL vão para `UNDER_REVIEW`. Valor igual ao limite é confirmado. Moeda diferente da do limite também vai para revisão. |
| Ciclo de vida        | `RECEIVED` -> `CONFIRMED`, `FAILED` ou `UNDER_REVIEW`. `UNDER_REVIEW` ainda pode virar `CONFIRMED` ou `FAILED`. `CONFIRMED` e `FAILED` são estados finais. |
| Erros de domínio     | `InvalidMoneyException`, `InvalidPaymentException` e `IllegalPaymentTransitionException`, todas derivadas de `DomainException`, para que adaptadores as traduzam em respostas adequadas. |

## Arquitetura

Arquitetura hexagonal (ports and adapters), com o núcleo em Java puro.

```
Adaptadores de entrada (REST, Camel)          Adaptadores de saída (JPA, RabbitMQ)
              |                                          ^
              v                                          |
        ReceivePaymentUseCase  ---- application ----  PaymentRepository
        (porta de entrada)                             PaymentEventPublisher
                                                       (portas de saída)
                                  |
                                  v
                     domain: Payment, Money, PaymentId,
                     PaymentStatus, PaymentReviewPolicy,
                     PaymentProcessedEvent, exceções
```

Princípios:

- **Regra de dependência**: as dependências apontam para dentro. `domain` não conhece ninguém; `application` conhece apenas `domain`.
- **Núcleo sem framework**: nenhuma classe em `domain` ou `application` importa Spring, Jakarta ou Camel. A ligação com o framework fica em adaptadores.
- **Value objects imutáveis**: `Money` e `PaymentId` são `record`s com validação no construtor.
- **Entidade com transições controladas**: `Payment` só muda de status por transições válidas definidas em `PaymentStatus`.
- **Tempo injetado**: `Clock` é passado ao caso de uso, o que permite testes determinísticos.
- **Testes com fakes em memória**: as portas de saída são substituídas por implementações simples, sem subir o Spring.

Fluxo do caso de uso `ReceivePayment`:

1. Busca um pagamento existente por `provider` e `externalId`; se houver, devolve o resultado com `duplicate = true`.
2. Converte valor e moeda do comando em `Money`.
3. Cria o `Payment` em `RECEIVED`.
4. Consulta `PaymentReviewPolicy` e move para `UNDER_REVIEW` ou `CONFIRMED`.
5. Salva pelo `PaymentRepository`.
6. Publica `PaymentProcessedEvent` pelo `PaymentEventPublisher`.
7. Devolve `ReceivePaymentResult`.

## Contrato do caso de uso

Entrada (`ReceivePaymentCommand`):

| Campo        | Tipo         | Descrição                                   |
| ------------ | ------------ | ------------------------------------------- |
| `provider`   | `String`     | Identificador do provedor de pagamento.     |
| `externalId` | `String`     | Identificador do evento no provedor.        |
| `amount`     | `BigDecimal` | Valor do pagamento, positivo.               |
| `currency`   | `String`     | Código ISO 4217, por exemplo `BRL`.         |

Saída (`ReceivePaymentResult`):

| Campo       | Tipo            | Descrição                                           |
| ----------- | --------------- | --------------------------------------------------- |
| `paymentId` | `PaymentId`     | Identificador interno do pagamento.                 |
| `status`    | `PaymentStatus` | Status resultante.                                  |
| `duplicate` | `boolean`       | `true` quando o evento já havia sido processado.    |

## Endpoints

Nenhum endpoint de negócio foi implementado ainda; o controller REST está previsto para a story PH-3 e este documento deve ser atualizado quando ele existir. O caso de uso acima é o contrato que o controller irá expor.

Endpoints disponíveis por dependências já configuradas:

| Método | Rota                        | Descrição                              |
| ------ | --------------------------- | -------------------------------------- |
| GET    | `/actuator/health`          | Saúde da aplicação (Spring Actuator)   |
| GET    | `/swagger-ui.html`          | Documentação OpenAPI (springdoc)       |

## Stack

| Área            | Tecnologia                                  |
| --------------- | ------------------------------------------- |
| Linguagem       | Java 21                                     |
| Framework       | Spring Boot 4.1                             |
| Integração      | Apache Camel 4.22 (planejado para as rotas) |
| Persistência    | PostgreSQL, Spring Data JPA, Flyway         |
| Mensageria      | RabbitMQ                                    |
| Mapeamento      | MapStruct, Lombok (apenas em adaptadores)   |
| Documentação    | springdoc-openapi                           |
| Testes          | JUnit, Testcontainers                       |
| Qualidade       | SonarQube (perfil opcional)                 |

## Infraestrutura local

O `docker-compose.yml` sobe as dependências:

| Serviço    | Perfil    | Portas        |
| ---------- | --------- | ------------- |
| PostgreSQL 17 | padrão | 5432          |
| RabbitMQ 4 (com painel) | padrão | 5672, 15672 |
| Redis 7    | `cache`   | 6379          |
| SonarQube  | `quality` | 9000          |

```bash
docker compose up -d                      # postgres e rabbitmq
docker compose --profile quality up -d    # inclui o SonarQube
docker compose --profile cache up -d      # inclui o Redis
```

## Como executar

Pré-requisitos: Java 21, Maven (ou o wrapper `./mvnw`) e Docker.

```bash
./mvnw test                  # testes
./mvnw spring-boot:run       # aplicação
```

## Estrutura do projeto

```
src/main/java/com/v1/paymenthub/
  domain/
    model/        Payment, Money, PaymentId, PaymentStatus
    policy/       PaymentReviewPolicy
    event/        PaymentProcessedEvent
    exception/    DomainException e derivadas
  application/
    port/in/      ReceivePaymentUseCase, Command e Result
    port/out/     PaymentRepository, PaymentEventPublisher
docs/             especificação das stories
```

## Roadmap

| Story | Escopo                                                           | Situação   |
| ----- | ---------------------------------------------------------------- | ---------- |
| PH-1  | Domínio e portas do caso de uso `ReceivePayment`                 | Em andamento (falta o serviço de aplicação e os testes) |
| PH-2  | Banco de dados, Flyway, JPA e MapStruct                          | Planejada  |
| PH-3  | Controller REST                                                  | Planejada  |
| PH-4  | Rotas Camel e RabbitMQ                                           | Planejada  |
