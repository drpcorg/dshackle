# Подпись ответов ключом авторизации

Дата: 2026-04-20
Статус: утверждено пользователем

## Цель

Свести две фичи — gRPC-авторизацию и подпись ответов — к одному источнику ключа. Убрать отдельную конфигурацию `signed-response`. Подпись ответов должна автоматически работать поверх того же RSA-ключа, который используется для подписи JWT при авторизации.

Мотивация: текущая конфигурация `signed-response` на практике почти никогда не заполняется, а отдельный EC-ключ для подписи ответов — избыточная сущность.

## Пользовательский контракт

Подпись ответов включается **автоматически** и применяется к вызову, если одновременно:

1. `auth.enabled = true`
2. `auth.server.provider-private-key` указан и читается как валидный PKCS#8 RSA-ключ
3. Клиент прислал `nonce` в gRPC-запросе (`NativeCallItem.nonce != 0`)

Если условия 1–2 выполнены, но клиент **не** шлёт `nonce` — подпись не формируется, ответ уходит как обычно.

Если клиент прислал `nonce`, но условия 1–2 не выполнены — dshackle возвращает клиенту ошибку `CODE_INTERNAL_ERROR` с текстом «Response signing requested via nonce but signing key is not configured». Молчаливая выдача неподписанного ответа при запрошенной подписи запрещена.

## Ключевые изменения

### Криптосхема

- Алгоритм: `SHA256withRSA` (BouncyCastle).
- Ключ: RSA, PKCS#8 PEM, тот же файл, что читает `auth.server.provider-private-key`.
- Обёртка сообщения остаётся прежней: `DSHACKLESIG/<nonce>/<source>/<hex-sha256(msg)>`.
- `keyId = firstLong(sha256(publicKey.encoded))` — формула та же, но численное значение изменится вместе со сменой ключа/алгоритма.

Это **breaking change** для клиентов, которые сегодня верифицируют подписи ECDSA NIST P-256: нужно обновить алгоритм верификации и перечитать публичный ключ.

### Конфигурация

Секция `signed-response` в YAML полностью удаляется. Поля `enabled`, `algorithm`, `private-key` больше не читаются и не документируются.

Если старый конфиг содержит `signed-response` — парсер его игнорирует. Отдельного warn/error не добавляем, чтобы не блокировать апгрейд: если секция есть, пользователь увидит в новой документации, что она не нужна.

### Удаление гейта в LocalReader

В `EthereumLocalReader.read()` сейчас есть блок:

```kotlin
if (key.nonce != null) {
    // we do not want to serve any requests (except hardcoded) that have nonces from cache
    return Mono.empty()
}
```

Этот блок удаляется. После изменения кешированные значения возвращаются и клиентам с `nonce` тоже. Подпись к ним применяется в `NativeCall.fetch()` (строка 426 на момент написания spec-а) — логика подписания там не меняется.

Безопасность: обёртка подписи включает `nonce` и `source`, поэтому кеш-хит даёт подпись, привязанную к конкретному вызову, а не к самому закешированному значению. Двум одинаковым кеш-хитам с разными `nonce` соответствуют разные подписи.

## Компоненты

### Удаляем

- `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfig.kt`
- `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfigReader.kt`
- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/EcdsaSigner.kt`
- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/NoSigner.kt`
- Поле `MainConfig.signature: SignatureConfig?`
- Вызов `signatureConfigReader.read(input)` в `MainConfigReader`
- Бин `signatureConfig(...)` в `Config.kt`
- Тесты: `SignatureConfigReaderSpec`, `EcdsaSignerSpec`, `ResponseSignerFactorySpec` (переписывается), фрагменты про `signed-response` в прочих тестах и тестовых ресурсах.

### Добавляем

- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/RsaSigner.kt` — реализация `ResponseSigner` на `SHA256withRSA`.
- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/DisabledSigner.kt` (или аналогичное имя) — реализация `ResponseSigner`, у которой `sign(...)` бросает `RpcException(CODE_INTERNAL_ERROR, ...)`. Используется, когда подпись запрошена, но ключа нет.

### Меняем

- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactory.kt` — теперь зависит от `AuthorizationConfig`:
  - `auth.enabled == false` → `DisabledSigner` + `INFO` лог.
  - `auth.server.providerPrivateKeyPath.isBlank()` → `DisabledSigner` + `WARN` лог.
  - Файл не найден / не PKCS#8 RSA → `IllegalStateException` при старте приложения (fail-fast, поведение аналогично `AuthorizationConfigReader`).
  - Ключ не RSA (например, EC) → `IllegalStateException("Only RSA keys are supported for response signing")`.
  - Иначе → `RsaSigner(privateKey, keyId)`.
- `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSigner.kt` — контракт `sign(...)` становится non-nullable: `fun sign(nonce: Long, message: ByteArray, source: String): Signature`. Бросает `RpcException`, если подпись невозможна.
- `src/main/kotlin/io/emeraldpay/dshackle/reader/RequestReaderFactory.kt` — `signer` в `ReaderData` и `getSignature` теперь non-nullable; убрать `?.` на вызове.
- `src/main/kotlin/io/emeraldpay/dshackle/Config.kt` — бин `signer` строится через новую фабрику из `AuthorizationConfig`.
- `src/main/kotlin/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReader.kt` — удалить гейт `if (key.nonce != null) return Mono.empty()`.
- `docs/reference-configuration.adoc` — убрать описание `signed-response`, дописать в раздел `auth`, что при настроенном ключе и присутствующем в запросе `nonce` ответы автоматически подписываются `SHA256withRSA`.

### Не меняем

- Интерфейс `ResponseSigner.Signature` (поля `value`, `upstreamId`, `keyId`).
- gRPC-протокол `NativeCallReplySignature` (никаких новых полей, алгоритм неявный).
- Логика вызова подписи в `NativeCall.fetch`, `NativeCall.executeOnRemote`, `NativeSubscribe`, `RequestReaderFactory.getSignature` — все гейты по `nonce != null` сохраняются.
- Формат обёртки сообщения и вычисление `keyId`.

## Поток данных

```
Клиент → NativeCall
  │
  ├─ nonce == null → обычный путь, без подписи
  │
  └─ nonce != null
       │
       ▼
  fetch(ctx):
    upstream.getLocalReader().read(...)      // hardcoded + кеш (теперь не отсекает nonce)
    ├─ hit → signer.sign(nonce, result, source)
    │       → CallResult.ok(..., signature)
    │
    └─ empty → executeOnRemote(ctx)
          │
          ├─ response.providedSignature != null → используем её
          │
          └─ иначе → signer.sign(nonce, result, upstreamId)
                   → CallResult.ok(..., signature)

signer.sign(...):
  RsaSigner      → возвращает Signature
  DisabledSigner → throw RpcException(CODE_INTERNAL_ERROR, "…signing key is not configured")
                   ↓
                   ловится в `.onErrorResume { CallResult.fail(...) }`
                   ↓
                   клиент получает gRPC-ошибку
```

## Обработка ошибок

| Ситуация | Поведение |
|---|---|
| `auth.enabled = false`, клиент без `nonce` | Обычный ответ, подписи нет |
| `auth.enabled = false`, клиент с `nonce` | Ошибка `CODE_INTERNAL_ERROR` клиенту |
| `auth.enabled = true`, путь к ключу пустой, `nonce` | Ошибка `CODE_INTERNAL_ERROR` клиенту |
| Путь к ключу есть, но файл отсутствует | Приложение не стартует (`IllegalStateException`) |
| Файл есть, но это не RSA (например, EC P-256) | Приложение не стартует (`IllegalStateException`) |
| `providedSignature` пришла от апстрим-dshackle | Используется без перекрытия |
| Кеш-хит + `nonce` | Кеш-значение возвращается и подписывается нашим ключом |

## Тестирование

- `RsaSignerSpec` (новый) — адаптированные сценарии из удаляемого `EcdsaSignerSpec`: подпись, верификация публичным ключом, стабильность `keyId`, разные `nonce`/`source` → разные подписи.
- `ResponseSignerFactorySpec` (переписан):
  - auth disabled → `DisabledSigner`, старт успешен, `sign()` бросает `RpcException`.
  - auth enabled + валидный RSA ключ → `RsaSigner`, подпись верифицируется.
  - auth enabled + пустой путь → `DisabledSigner` + warn.
  - auth enabled + файл отсутствует → `IllegalStateException` при старте.
  - auth enabled + EC-ключ → `IllegalStateException`.
- `EthereumLocalReaderSpec` — обновить: запрос с `nonce` теперь обслуживается из кеша, а не возвращает empty.
- `NativeCallSpec` / `NativeCallTest` — добавить кейс: кеш-хит + `nonce` → в `CallResult` есть валидная подпись.
- `MainConfigReaderSpec` — убрать проверки для `signed-response`.
- Удалить `SignatureConfigReaderSpec`, `EcdsaSignerSpec`, фрагменты про `signed-response` в `test/resources/configs/*.yaml`.

## Миграция и обратная совместимость

- **Breaking:** алгоритм подписи ответов меняется с ECDSA NIST P-256 на RSA. Клиенты, которые верифицируют подписи, должны перейти на `SHA256withRSA` и перечитать публичный ключ (`keyId` также изменится).
- **Breaking:** секция `signed-response` в YAML игнорируется. Пользователи, которые её настраивали, после апгрейда увидят, что подпись работает через `auth.server.provider-private-key` — отдельного файла ключа больше не требуется.
- **Не breaking:** для пользователей, у которых `auth` не включён, ничего не меняется — подписи не было и не будет.

Release notes и changelog должны явно перечислить оба пункта.
