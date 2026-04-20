# Response Signing With Auth Key — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Убрать отдельную `SignatureConfig`. Подписывать gRPC-ответы тем же RSA-ключом, что используется для `auth` (JWT RS256). Подпись включается автоматически для запросов с `nonce`, когда `auth.enabled=true` и ключ настроен. Если `nonce` пришёл, а ключа нет — отдавать клиенту ошибку. Дополнительно: снять в `EthereumLocalReader` блокировку, из-за которой кешированные значения не возвращались клиентам с `nonce`.

**Architecture:** Заменяем `EcdsaSigner` на `RsaSigner` (`SHA256withRSA`), сохраняя обёртку сообщения `DSHACKLESIG/<nonce>/<source>/<hex-sha256(msg)>` и формулу `keyId = firstLong(sha256(pub.encoded))`. Новая `ResponseSignerFactory` читает путь к ключу из `AuthorizationConfig`, fail-fast на старте на битый/не-RSA ключ; при `nonce` без ключа `sign()` бросает `RpcException(CODE_INTERNAL_ERROR)`. Клиентское поведение без `nonce` не меняется.

**Tech Stack:** Kotlin 1.x (main), Groovy Spock (tests), BouncyCastle RSA, Spring Boot DI, Reactor.

---

## File Structure

- **Create**
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/RsaSigner.kt`
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/DisabledSigner.kt`
  - `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/RsaSignerSpec.groovy`
  - `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/DisabledSignerSpec.groovy`
- **Modify**
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSigner.kt` — `sign()` non-nullable return.
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactory.kt` — полностью переписан.
  - `src/main/kotlin/io/emeraldpay/dshackle/reader/RequestReaderFactory.kt` — `signer` non-nullable.
  - `src/main/kotlin/io/emeraldpay/dshackle/Config.kt` — убрать бин `signatureConfig`.
  - `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfig.kt` — убрать поле `signature`.
  - `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfigReader.kt` — убрать `signatureConfigReader`.
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReader.kt` — снять nonce-гейт.
  - `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactorySpec.groovy` — переписан.
  - `src/test/groovy/io/emeraldpay/dshackle/rpc/NativeSubscribeSpec.groovy` — заменить `NoSigner` на `DisabledSigner`.
  - `src/test/groovy/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReaderSpec.groovy` — инвертировать тест для `nonce`.
  - `docs/reference-configuration.adoc` — убрать секцию `signed-response`, дополнить `auth`.
- **Delete**
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/EcdsaSigner.kt`
  - `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/NoSigner.kt`
  - `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfig.kt`
  - `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfigReader.kt`
  - `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/EcdsaSignerSpec.groovy`
  - `src/test/groovy/io/emeraldpay/dshackle/config/SignatureConfigReaderSpec.groovy`

---

## Task 1: RsaSigner (core крипто)

**Files:**
- Create: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/RsaSigner.kt`
- Test: `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/RsaSignerSpec.groovy`

### Step 1: Написать падающий тест `RsaSignerSpec`

- [ ] Создать `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/RsaSignerSpec.groovy`:

```groovy
package io.emeraldpay.dshackle.upstream.signature

import org.apache.commons.codec.binary.Hex
import org.bouncycastle.jce.provider.BouncyCastleProvider
import spock.lang.Specification

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateKey

class RsaSignerSpec extends Specification {

    def setupSpec() {
        Security.addProvider(new BouncyCastleProvider())
    }

    def "Wrap message"() {
        setup:
        def signer = new RsaSigner(Stub(RSAPrivateKey), 100L)

        when:
        def act = signer.wrapMessage(10, "test".bytes, "infura")

        then:
        act == "DSHACKLESIG/10/infura/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }

    def "Signed message is valid"() {
        setup:
        def result = "test".bytes
        def keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        def pair = keyGen.generateKeyPair()

        def sha256 = MessageDigest.getInstance("SHA-256")
        def verifier = Signature.getInstance("SHA256withRSA", "BC")
        verifier.initVerify(pair.getPublic())
        verifier.update("DSHACKLESIG/10/infura/${Hex.encodeHexString(sha256.digest(result))}".getBytes())

        def signer = new RsaSigner((pair.getPrivate() as RSAPrivateKey), 100L)

        when:
        def sig = signer.sign(10, result, "infura")

        then:
        verifier.verify(sig.value)
        sig.upstreamId == "infura"
        sig.keyId == 100L
    }

    def "Different nonce produces different signature"() {
        setup:
        def keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        def pair = keyGen.generateKeyPair()
        def signer = new RsaSigner((pair.getPrivate() as RSAPrivateKey), 1L)

        when:
        def sig1 = signer.sign(1, "test".bytes, "up")
        def sig2 = signer.sign(2, "test".bytes, "up")

        then:
        !Arrays.equals(sig1.value, sig2.value)
    }
}
```

### Step 2: Запустить тест, убедиться что падает

- [ ] `./gradlew test --tests RsaSignerSpec`
- Expected: compilation error / class not found `RsaSigner`.

### Step 3: Реализовать `RsaSigner`

- [ ] Создать `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/RsaSigner.kt`:

```kotlin
package io.emeraldpay.dshackle.upstream.signature

import org.apache.commons.codec.binary.Hex
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey

class RsaSigner(
    private val privateKey: RSAPrivateKey,
    val keyId: Long,
) : ResponseSigner {

    companion object {
        const val SIGN_SCHEME = "SHA256withRSA"
        const val MSG_PREFIX = "DSHACKLESIG"
        const val MSG_SEPARATOR = '/'
    }

    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature {
        val sig = Signature.getInstance(SIGN_SCHEME, "BC")
        sig.initSign(privateKey)
        val wrapped = wrapMessage(nonce, message, source)
        sig.update(wrapped.toByteArray())
        val value = sig.sign()
        return ResponseSigner.Signature(value, source, keyId)
    }

    /**
     * Same wrapping as EcdsaSigner: `"DSHACKLESIG/" || str(nonce) || "/" || source || "/" || hex(sha256(msg))`
     */
    fun wrapMessage(nonce: Long, message: ByteArray, source: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val formatterMsg = StringBuilder(11 + 1 + 18 + 1 + 64 + 1 + 64)
        formatterMsg.append(MSG_PREFIX)
            .append(MSG_SEPARATOR)
            .append(nonce.toString())
            .append(MSG_SEPARATOR)
            .append(source)
            .append(MSG_SEPARATOR)
            .append(Hex.encodeHexString(sha256.digest(message)))
        return formatterMsg.toString()
    }
}
```

Примечание: `RsaSigner` временно НЕ реализует `sign()` из текущего nullable-интерфейса — это сработает, потому что Kotlin non-null `Signature` является подтипом `Signature?`. Изменение сигнатуры интерфейса делаем в Task 3.

### Step 4: Запустить тест, убедиться что проходит

- [ ] `./gradlew test --tests RsaSignerSpec`
- Expected: 3/3 passed.

### Step 5: Коммит

- [ ] ```bash
git add src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/RsaSigner.kt \
        src/test/groovy/io/emeraldpay/dshackle/upstream/signature/RsaSignerSpec.groovy
git commit -m "feat(signature): add RsaSigner using SHA256withRSA"
```

---

## Task 2: DisabledSigner

**Files:**
- Create: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/DisabledSigner.kt`
- Test: `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/DisabledSignerSpec.groovy`

### Step 1: Падающий тест

- [ ] Создать `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/DisabledSignerSpec.groovy`:

```groovy
package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import spock.lang.Specification

class DisabledSignerSpec extends Specification {

    def "sign throws RpcException with CODE_INTERNAL_ERROR"() {
        setup:
        def signer = new DisabledSigner()

        when:
        signer.sign(1L, "data".bytes, "upstreamId")

        then:
        def ex = thrown(RpcException)
        ex.code == -32603
        ex.rpcMessage.contains("signing key is not configured")
    }
}
```

### Step 2: Запустить, убедиться что падает

- [ ] `./gradlew test --tests DisabledSignerSpec`
- Expected: compilation error — `DisabledSigner` не существует.

### Step 3: Реализовать

- [ ] Создать `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/DisabledSigner.kt`:

```kotlin
package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError

class DisabledSigner : ResponseSigner {
    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature {
        throw RpcException(
            RpcResponseError.CODE_INTERNAL_ERROR,
            "Response signing requested via nonce but signing key is not configured",
        )
    }
}
```

### Step 4: Убедиться, что тест проходит

- [ ] `./gradlew test --tests DisabledSignerSpec`
- Expected: 1/1 passed.

### Step 5: Коммит

- [ ] ```bash
git add src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/DisabledSigner.kt \
        src/test/groovy/io/emeraldpay/dshackle/upstream/signature/DisabledSignerSpec.groovy
git commit -m "feat(signature): add DisabledSigner that throws on sign()"
```

---

## Task 3: Сделать `ResponseSigner.sign()` non-nullable, адаптировать вызовы

**Files:**
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSigner.kt:5`
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/reader/RequestReaderFactory.kt:22,43-49,85`

### Step 1: Изменить интерфейс `ResponseSigner`

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSigner.kt` строка 5:

Было:
```kotlin
fun sign(nonce: Long, message: ByteArray, source: String): Signature?
```

Стало:
```kotlin
fun sign(nonce: Long, message: ByteArray, source: String): Signature
```

### Step 2: Сделать `signer` в `RequestReader` non-nullable

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/reader/RequestReaderFactory.kt`:

Строка 22, было:
```kotlin
abstract class RequestReader(
    private val signer: ResponseSigner?,
) : Reader<ChainRequest, Result> {
```

Стало:
```kotlin
abstract class RequestReader(
    private val signer: ResponseSigner,
) : Reader<ChainRequest, Result> {
```

Строки 43-49, было:
```kotlin
protected fun getSignature(key: ChainRequest, response: ChainResponse, upstreamId: String) =
    response.providedSignature
        ?: if (key.nonce != null) {
            signer?.sign(key.nonce, response.getResult(), upstreamId)
        } else {
            null
        }
```

Стало:
```kotlin
protected fun getSignature(key: ChainRequest, response: ChainResponse, upstreamId: String) =
    response.providedSignature
        ?: if (key.nonce != null) {
            signer.sign(key.nonce, response.getResult(), upstreamId)
        } else {
            null
        }
```

Строка 85 в `data class ReaderData`, было:
```kotlin
val signer: ResponseSigner?,
```

Стало:
```kotlin
val signer: ResponseSigner,
```

### Step 3: Проверить, что ни одно место не ломается из-за non-null

- [ ] Убедиться, что нигде `signer?.sign` больше не упоминается:

```bash
rg 'signer\?\.sign' src/main
```

Ожидаемый вывод: пусто.

- [ ] Найти других мест, где может передаваться nullable `signer`:

```bash
rg 'ResponseSigner\?' src/main
```

Ожидаемо `BroadcastReader`, `QuorumRequestReader` принимают `signer` и передают дальше; они получают его из `ReaderData.signer`, которое теперь non-null. Если в этих файлах параметр всё ещё nullable — убрать `?`.

### Step 4: Сборка

- [ ] `./gradlew compileKotlin compileTestKotlin compileTestGroovy`
- Expected: success. Если в тестах где-то передавался `null` как signer — компиляция упадёт и даст точное место.

### Step 5: Коммит

- [ ] ```bash
git add src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSigner.kt \
        src/main/kotlin/io/emeraldpay/dshackle/reader/RequestReaderFactory.kt
git commit -m "refactor(signature): ResponseSigner.sign returns non-null Signature"
```

---

## Task 4: Переписать `ResponseSignerFactory` под `AuthorizationConfig`

**Files:**
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactory.kt` (переписывается целиком)
- Test: `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactorySpec.groovy` (переписывается)

### Step 1: Падающий тест для фабрики

- [ ] Заменить содержимое `src/test/groovy/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactorySpec.groovy` целиком:

```groovy
package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.config.AuthorizationConfig
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.util.ResourceUtils
import spock.lang.Specification

import java.security.Security

class ResponseSignerFactorySpec extends Specification {

    def setupSpec() {
        Security.addProvider(new BouncyCastleProvider())
    }

    def "DisabledSigner when auth disabled"() {
        setup:
        def auth = AuthorizationConfig.default()

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof DisabledSigner
    }

    def "DisabledSigner when provider-private-key path is blank"() {
        setup:
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig("", "classpath:keys/public.pem"),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof DisabledSigner
    }

    def "RsaSigner built from valid RSA key"() {
        setup:
        def privPath = ResourceUtils.getFile("classpath:keys/priv.p8.key").absolutePath
        def pubPath = ResourceUtils.getFile("classpath:keys/public.pem").absolutePath
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig(privPath, pubPath),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        def signer = new ResponseSignerFactory(auth).createSigner()

        then:
        signer instanceof RsaSigner
        (signer as RsaSigner).keyId != 0L
    }

    def "Fails on missing key file"() {
        setup:
        def auth = new AuthorizationConfig(
                true,
                "owner",
                new AuthorizationConfig.ServerConfig("/no/such/file.pem", "classpath:keys/public.pem"),
                AuthorizationConfig.ClientConfig.default(),
        )

        when:
        new ResponseSignerFactory(auth).createSigner()

        then:
        thrown(Exception)
    }

    def "DisabledSigner.sign throws RpcException"() {
        setup:
        def signer = new ResponseSignerFactory(AuthorizationConfig.default()).createSigner()

        when:
        signer.sign(1L, "data".bytes, "up")

        then:
        def ex = thrown(RpcException)
        ex.code == -32603
    }
}
```

Примечание: в тесте `Fails on missing key file` используется широкое `thrown(Exception)`, потому что JVM выбросит `NoSuchFileException` из `Files.readString` (подкласс `IOException`), а не `IllegalStateException`. Код фабрики это не ловит — это ожидаемое fail-fast поведение.

### Step 2: Тест падает

- [ ] `./gradlew test --tests ResponseSignerFactorySpec`
- Expected: компиляция падает — старый конструктор `ResponseSignerFactory(SignatureConfig)` не соответствует новому тесту.

### Step 3: Переписать `ResponseSignerFactory`

- [ ] Заменить содержимое `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactory.kt` целиком:

```kotlin
package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.config.AuthorizationConfig
import org.apache.commons.codec.binary.Hex
import org.bouncycastle.openssl.PEMParser
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

@Configuration
open class SignatureBeans {
    @Bean
    open fun signer(factory: ResponseSignerFactory): ResponseSigner =
        factory.createSigner()
}

@Component
open class ResponseSignerFactory(
    private val authorizationConfig: AuthorizationConfig,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ResponseSignerFactory::class.java)
    }

    fun createSigner(): ResponseSigner {
        if (!authorizationConfig.enabled) {
            log.info("Response signing disabled: auth is not enabled")
            return DisabledSigner()
        }
        val path = authorizationConfig.serverConfig.providerPrivateKeyPath
        if (path.isBlank()) {
            log.warn("Response signing disabled: auth.server.provider-private-key is not set")
            return DisabledSigner()
        }

        val (privateKey, keyId) = readRsaKey(path)
        return RsaSigner(privateKey, keyId)
    }

    internal fun readRsaKey(path: String): Pair<RSAPrivateKey, Long> {
        val pemContent = StringReader(Files.readString(Paths.get(path)))
        val pemObject = PEMParser(pemContent).readPemObject()
            ?: throw IllegalStateException("Cannot parse PEM key at $path")

        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pemObject.content))

        if (privateKey !is RSAPrivateKey) {
            throw IllegalStateException("Only RSA keys are supported for response signing")
        }

        val publicKey = extractPublicKey(keyFactory, privateKey)
        val keyId = getPublicKeyId(publicKey)
        return Pair(privateKey, keyId)
    }

    private fun extractPublicKey(keyFactory: KeyFactory, privateKey: RSAPrivateKey): PublicKey {
        val crt = privateKey as? RSAPrivateCrtKey
            ?: throw IllegalStateException("RSA private key does not expose public exponent; use a PKCS#8 key that contains CRT parameters")
        val spec = RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        return keyFactory.generatePublic(spec)
    }

    private fun getPublicKeyId(publicKey: PublicKey): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullId = digest.digest(publicKey.encoded)
        log.info("Using key to sign responses: ${Hex.encodeHexString(fullId).substring(0..15)}")
        return ByteBuffer.wrap(fullId).asLongBuffer().get()
    }
}
```

### Step 4: Тест проходит

- [ ] `./gradlew test --tests ResponseSignerFactorySpec`
- Expected: 5/5 passed. Если падает «Only RSA keys are supported» на `priv.p8.key` — это значит ключ не RSA; проверить файл. В `RsaKeyReader.kt` он уже используется как RSA (см. `GrpcUpstreamsAuth`), так что должен подойти.

### Step 5: Коммит

- [ ] ```bash
git add src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactory.kt \
        src/test/groovy/io/emeraldpay/dshackle/upstream/signature/ResponseSignerFactorySpec.groovy
git commit -m "refactor(signature): build signer from AuthorizationConfig"
```

---

## Task 5: Убрать `SignatureConfig`, бин, поле, чтение YAML

**Files:**
- Delete: `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfig.kt`
- Delete: `src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfigReader.kt`
- Delete: `src/test/groovy/io/emeraldpay/dshackle/config/SignatureConfigReaderSpec.groovy`
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfig.kt:44`
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfigReader.kt:36,78-80`
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/Config.kt:27,133-136`

### Step 1: Удалить поле `signature` из `MainConfig`

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfig.kt` удалить строку 44:

```kotlin
var signature: SignatureConfig? = null
```

### Step 2: Убрать `signatureConfigReader` из `MainConfigReader`

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/config/MainConfigReader.kt`:

Удалить строку 36:
```kotlin
private val signatureConfigReader = SignatureConfigReader(fileResolver)
```

Удалить строки 78-80:
```kotlin
signatureConfigReader.read(input).let {
    config.signature = it
}
```

### Step 3: Убрать бин и импорт в `Config.kt`

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/Config.kt`:

Удалить строку 27:
```kotlin
import io.emeraldpay.dshackle.config.SignatureConfig
```

Удалить строки 133-136:
```kotlin
@Bean
open fun signatureConfig(@Autowired mainConfig: MainConfig): SignatureConfig {
    return mainConfig.signature ?: SignatureConfig()
}
```

### Step 4: Удалить классы и тест

- [ ] ```bash
rm src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfig.kt \
   src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfigReader.kt \
   src/test/groovy/io/emeraldpay/dshackle/config/SignatureConfigReaderSpec.groovy
```

### Step 5: Если `EcdsaSignerSpec` всё ещё импортирует `SignatureConfig` — удалить его тоже

- [ ] ```bash
rm -f src/test/groovy/io/emeraldpay/dshackle/upstream/signature/EcdsaSignerSpec.groovy
```

Это опережает Task 6 Step 1, но нужно, чтобы компиляция прошла. В Task 6 просто повторим `rm -f` (no-op, если файла уже нет).

### Step 6: Сборка

- [ ] `./gradlew compileKotlin compileTestKotlin compileTestGroovy`
- Expected: success.

### Step 7: Коммит

- [ ] ```bash
git add -A src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfig.kt \
          src/main/kotlin/io/emeraldpay/dshackle/config/SignatureConfigReader.kt \
          src/test/groovy/io/emeraldpay/dshackle/config/SignatureConfigReaderSpec.groovy \
          src/test/groovy/io/emeraldpay/dshackle/upstream/signature/EcdsaSignerSpec.groovy \
          src/main/kotlin/io/emeraldpay/dshackle/config/MainConfig.kt \
          src/main/kotlin/io/emeraldpay/dshackle/config/MainConfigReader.kt \
          src/main/kotlin/io/emeraldpay/dshackle/Config.kt
git commit -m "refactor(config): remove signed-response YAML section and SignatureConfig"
```

---

## Task 6: Удалить `EcdsaSigner`, `NoSigner`, обновить использования

**Files:**
- Delete: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/EcdsaSigner.kt`
- Delete: `src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/NoSigner.kt`
- Modify: `src/test/groovy/io/emeraldpay/dshackle/rpc/NativeSubscribeSpec.groovy:25,33`

### Step 1: Удалить производственные файлы

- [ ] ```bash
rm src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/EcdsaSigner.kt \
   src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/NoSigner.kt
rm -f src/test/groovy/io/emeraldpay/dshackle/upstream/signature/EcdsaSignerSpec.groovy
```

### Step 2: В `NativeSubscribeSpec.groovy` заменить `NoSigner` на `DisabledSigner`

- [ ] В `src/test/groovy/io/emeraldpay/dshackle/rpc/NativeSubscribeSpec.groovy`:

Строка 25, было:
```groovy
import io.emeraldpay.dshackle.upstream.signature.NoSigner
```

Стало:
```groovy
import io.emeraldpay.dshackle.upstream.signature.DisabledSigner
```

Строка 33, было:
```groovy
def signer = new NoSigner()
```

Стало:
```groovy
def signer = new DisabledSigner()
```

### Step 3: Проверить, что тесты в `NativeSubscribeSpec` не зависели от `NoSigner`-поведения «возвращает null»

- [ ] ```bash
rg -n 'holder\.nonce|\.nonce\s*=' src/test/groovy/io/emeraldpay/dshackle/rpc/NativeSubscribeSpec.groovy
```

Если в каком-то тесте устанавливается `nonce != null` и при этом вызывается sign-путь, `DisabledSigner.sign()` бросит — тест сломается. В этом случае заменить signer на мок с нужным поведением:

```groovy
def signer = Mock(ResponseSigner) {
    _ * sign(_, _, _) >> new ResponseSigner.Signature("sig".bytes, "up", 1L)
}
```

и добавить импорт `io.emeraldpay.dshackle.upstream.signature.ResponseSigner`. На момент написания плана единственный `def signer` в файле — это общая переменная для всех тестов, и `nonce` в них не задаётся, поэтому замены достаточно. Если появится новый кейс — обновить точечно.

### Step 4: Сборка + тесты подписного пакета и связанных

- [ ] ```bash
./gradlew test --tests 'io.emeraldpay.dshackle.upstream.signature.*' \
              --tests 'io.emeraldpay.dshackle.rpc.NativeSubscribeSpec' \
              --tests 'io.emeraldpay.dshackle.config.MainConfigReaderSpec'
```
- Expected: all passed.

### Step 5: Коммит

- [ ] ```bash
git add -A src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/EcdsaSigner.kt \
          src/main/kotlin/io/emeraldpay/dshackle/upstream/signature/NoSigner.kt \
          src/test/groovy/io/emeraldpay/dshackle/rpc/NativeSubscribeSpec.groovy
git commit -m "refactor(signature): remove EcdsaSigner and NoSigner"
```

---

## Task 7: Снять nonce-гейт в `EthereumLocalReader` и инвертировать его тест

**Files:**
- Modify: `src/main/kotlin/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReader.kt:51-54`
- Modify: `src/test/groovy/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReaderSpec.groovy:33-49`

### Step 1: Убедиться, что существующий тест «Returns empty if nonce set» проходит до изменений

- [ ] `./gradlew test --tests 'io.emeraldpay.dshackle.upstream.ethereum.EthereumLocalReaderSpec'`
- Expected: passed. Тест проверяет текущее поведение гейта.

### Step 2: Снять гейт в `EthereumLocalReader`

- [ ] В `src/main/kotlin/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReader.kt` удалить строки 51-54:

```kotlin
        if (key.nonce != null) {
            // we do not want to serve any requests (except hardcoded) that have nonces from cache
            return Mono.empty()
        }
```

После удаления метод `read(key)` выглядит так:

```kotlin
override fun read(key: ChainRequest): Mono<ChainResponse> {
    if (methods.isHardcoded(key.method)) {
        return Mono.just(methods.executeHardcoded(key.method))
            .map { ChainResponse(it, null) }
    }
    if (!methods.isCallable(key.method)) {
        return Mono.error(RpcException(RpcResponseError.CODE_METHOD_NOT_EXIST, "Unsupported method"))
    }
    return commonRequests(key)?.switchIfEmpty {
        Mono.just(ChainResponse(nullValue, null, emptyList()))
    } ?: Mono.empty()
}
```

### Step 3: Убедиться, что старый тест теперь падает

- [ ] `./gradlew test --tests 'io.emeraldpay.dshackle.upstream.ethereum.EthereumLocalReaderSpec'`
- Expected: тест `Returns empty if nonce set` падает (`act != null`), остальные проходят.

### Step 4: Инвертировать тест под новое поведение

- [ ] В `src/test/groovy/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReaderSpec.groovy` заменить строки 33-49 (тест `Returns empty if nonce set`) на:

```groovy
def "Serves non-hardcoded call when nonce is set"() {
    setup:
    def methods = new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
    def router = new EthereumLocalReader(
            new EthereumCachingReader(
                    TestingCommons.multistream(TestingCommons.api()),
                    Caches.default(),
                    ConstantFactory.constantFactory(new DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)),
            ),
            methods
    )
    when:
    // с пустым кешем eth_getTransactionByHash уйдёт в switchIfEmpty и вернёт nullValue,
    // но больше не Mono.empty() — гейт по nonce снят
    def act = router.read(new ChainRequest("eth_getTransactionByHash",
                    new ListParams(["0x0000000000000000000000000000000000000000000000000000000000000001"]),
                    10))
            .block(Duration.ofSeconds(1))
    then:
    act != null
}
```

### Step 5: Тест проходит

- [ ] `./gradlew test --tests 'io.emeraldpay.dshackle.upstream.ethereum.EthereumLocalReaderSpec'`
- Expected: все тесты прошли.

### Step 6: Проверить, что другие тесты не опираются на старое поведение

- [ ] ```bash
rg -n 'nonce' src/test/groovy/io/emeraldpay/dshackle/upstream/ethereum/
rg -n 'returns empty|empty.*nonce|nonce.*empty' src/test/groovy -i
```
Если найдутся прочие тесты с ожиданием «при nonce кеш обходится» — привести в соответствие.

### Step 7: Коммит

- [ ] ```bash
git add src/main/kotlin/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReader.kt \
        src/test/groovy/io/emeraldpay/dshackle/upstream/ethereum/EthereumLocalReaderSpec.groovy
git commit -m "refactor(local-reader): serve cached results for requests with nonce"
```

---

## Task 8: Документация

**Files:**
- Modify: `docs/reference-configuration.adoc`

### Step 1: Найти точные координаты упоминаний `signed-response`

- [ ] ```bash
rg -n 'signed-response' docs/reference-configuration.adoc
```

Ожидаемо 5 попаданий (пример YAML у ~строки 50, пункт таблицы у ~строк 210/213, заголовок секции у ~строки 562, пример в теле секции у ~строки 567).

### Step 2: Удалить пример YAML из вводного блока

- [ ] В `docs/reference-configuration.adoc` удалить строки содержащие:

```yaml
signed-response:
  enabled: true
  algorithm: SECP256K1
```
(вступительный YAML-пример у строк ~50-52, в блоке `[source,yaml]`).

### Step 3: Удалить строки из таблицы свойств

- [ ] Удалить блок у строк ~210-213:

```asciidoc
| `signed-response`
|
| Signed responses
See <<signed-response>> section.
```

### Step 4: Удалить всю секцию `[#signed-response]`

- [ ] Удалить секцию целиком — от заголовка `[#signed-response]` и `== Signed Response` до следующего заголовка уровня `==` или `[#...]`. Сохранить перенос строки перед следующей секцией.

### Step 5: Добавить подсекцию про подпись в раздел `auth`

- [ ] Найти в `docs/reference-configuration.adoc` раздел про `authorization`/`auth` конфигурацию (`rg -n '== Authoriz|^== Auth|^\[#auth' docs/reference-configuration.adoc`). В конец этого раздела, перед следующим заголовком уровня `==`, добавить:

```asciidoc
==== Response Signing

When `auth.enabled` is `true` and `auth.server.provider-private-key` points to a valid
PKCS#8 RSA private key, dshackle automatically signs gRPC responses with `SHA256withRSA`
for any `NativeCall` request that provides a non-zero `nonce`. The same key used for
issuing JWT tokens (RS256) is reused for response signatures — no separate configuration
is required.

If a client sends a nonce but the signing key is not configured (auth disabled or the
path is empty), dshackle returns an error with code `-32603` and message
"Response signing requested via nonce but signing key is not configured".
```

Если раздела `auth` в документации нет — создать его с минимальным описанием и приложить туда подсекцию про подпись.

### Step 6: Проверка

- [ ] ```bash
rg -n 'signed-response' docs/reference-configuration.adoc
```
- Expected: пусто.

### Step 7: Коммит

- [ ] ```bash
git add docs/reference-configuration.adoc
git commit -m "docs: replace signed-response section with auth-based signing"
```

---

## Task 9: Финальная проверка сборки и всех тестов

### Step 1: Полная сборка + все тесты

- [ ] ```bash
./gradlew build
```
- Expected: BUILD SUCCESSFUL.

### Step 2: Пройтись grep'ом по остаткам старой конфигурации

- [ ] ```bash
rg -n 'SignatureConfig|signed-response|EcdsaSigner|NoSigner' src/ docs/
```
- Expected: совпадения только в `docs/superpowers/specs/*` и `docs/superpowers/plans/*` (исторические документы). Иные совпадения — исправить и повторить.

### Step 3: Финальный коммит, если что-то дочищалось

- [ ] (только если в Step 2 что-то правилось) ```bash
git add -A && git commit -m "chore: cleanup leftover references"
```

---

## Self-Review

- **Spec coverage:**
  - Убираем `SignatureConfig` — Task 5.
  - Новый `RsaSigner` — Task 1.
  - `DisabledSigner` с throw — Task 2.
  - Контракт `sign()` non-null — Task 3.
  - Фабрика читает ключ из `AuthorizationConfig` — Task 4.
  - Удаление `EcdsaSigner`/`NoSigner` — Task 6.
  - Снятие cache-gate в `EthereumLocalReader` — Task 7.
  - Документация (`signed-response` удалена, `auth` дополнен) — Task 8.
  - Финальная верификация — Task 9.
  - Fail-fast при битом ключе — покрывается тестом `Fails on missing key file` в Task 4 Step 1.

- **Placeholder scan:** кодовые блоки содержат конкретный код. В Task 6 Step 3 есть условная замена signer на Mock — условие сформулировано чётко, со ссылкой на конкретный факт о текущем файле.

- **Type consistency:**
  - `ResponseSigner.Signature(value, upstreamId, keyId)` — одинаково во всех тестах и коде.
  - `RsaSigner(privateKey: RSAPrivateKey, keyId: Long)` — единый конструктор.
  - `ResponseSignerFactory(authorizationConfig: AuthorizationConfig)` — единая сигнатура.
  - `ResponseSigner.sign(nonce, message, source): Signature` (non-null) — используется в Task 1, 2, 3, 4 согласованно.
