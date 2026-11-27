package com.jarvis.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCardStateTest {
    @Test fun knownIncomingMatchesApprovedCard() {
        val card = presentCall(CallFixtures.lauraIncoming())
        assertTrue(card.visible)
        assertEquals("lime", card.border)
        assertEquals("AHORA", card.badge)
        assertEquals("LLAMADA ENTRANTE", card.heading)
        assertEquals("Laura", card.name)
        assertEquals("+34 629 14 82 07", card.numberLine)
        assertEquals("IA Premium contestando...", card.aiLine)
        assertNull(card.banner)
        assertEquals(listOf("Coger", "Dejar a la IA"), card.buttons.map { it.label })
        assertEquals("Ha entrado una llamada. La IA Premium ya está contestando.", card.homeSubtitle)
        assertEquals("¿Dejo que la IA siga con la llamada?", card.bottomPrompt)
        assertTrue(spokenPrompt(CallFixtures.lauraIncoming())!!.contains("IA Premium"))
    }

    @Test fun unknownBusinessShowsSourceAndBanner() {
        val card = presentCall(CallFixtures.iratiUnknown())
        assertEquals("orange", card.border)
        assertEquals("DESCONOCIDO", card.badge)
        assertEquals("Distribuciones Irati", card.name)
        assertEquals("No está en tus contactos · Vía Google Maps", card.banner)
        assertTrue(card.detailLine!!.contains("4,3"))
        assertTrue(card.detailLine!!.contains("Google Maps"))
        assertEquals("Polígono Agustinos, nave 18, Pamplona", card.address)
        assertEquals(listOf(CallButton.ANSWER, CallButton.LEAVE_AI), card.buttons.map { it.action })
    }

    @Test fun liveTranscriptOffersTakeMuteHangup() {
        val card = presentCall(CallFixtures.lauraLive())
        assertEquals("EN CURSO", card.badge)
        assertEquals(4, card.transcript.size)
        assertEquals("LAURA", card.transcript[1].speaker)
        assertEquals("Laura quiere hablar contigo. ¿Coges la llamada?", card.askLine)
        assertEquals(listOf("Coger la llamada", "Silenciar", "Colgar"), card.buttons.map { it.label })
        assertEquals("0:42", CallFixtures.lauraLive().ageLabel)
        assertTrue(card.meta.contains("InitTPV"))
    }

    @Test fun videoCardKeepsCameraOffAndDocumentsMissingPreview() {
        val unavailable = presentCall(CallFixtures.lauraVideoUnavailable())
        assertEquals("VIDEOLLAMADA WHATSAPP", unavailable.heading)
        assertFalse(unavailable.showVideoFrame)
        assertTrue(unavailable.videoNote!!.contains("no puede mostrar el vídeo"))
        assertTrue(unavailable.videoNote!!.contains("cámara"))
        assertEquals(listOf("Coger con vídeo", "Solo voz", "Dejar a la IA"), unavailable.buttons.map { it.label })
        assertEquals("Tu cámara y tu micro siguen apagados", unavailable.bottomDetail)
        assertEquals(1, unavailable.transcript.size)

        val live = presentCall(CallFixtures.lauraVideoLive())
        assertTrue(live.showVideoFrame)
        assertEquals("Vista previa · no se envía tu cámara/micro", live.videoNote)
    }

    @Test fun endedCallHidesTheCard() {
        val card = presentCall(CallFixtures.lauraIncoming().copy(active = false, phase = CallPhase.ENDED))
        assertFalse(card.visible)
        assertNull(spokenPrompt(CallFixtures.lauraIncoming().copy(phase = CallPhase.ENDED)))
    }
}

class IdentityTest {
    @Test fun phoneContactWinsOverMapsAndWeb() {
        val chosen = chooseIdentity(listOf(
            IdentityHit(IdentityOrigin.WEB, "Otra empresa", "Web"),
            IdentityHit(IdentityOrigin.MAPS, "Distribuciones Irati", "Google Maps", ratingText = "4,3"),
            IdentityHit(IdentityOrigin.PHONE, "Laura", "Contacto")
        ))
        assertEquals("Laura", chosen?.name)
        assertEquals(IdentityOrigin.PHONE, chosen?.origin)
    }

    @Test fun mapsIsUsedWhenTheNumberIsNotAContact() {
        val chosen = chooseIdentity(listOf(
            IdentityHit(IdentityOrigin.WEB, "Mención suelta", "Web"),
            IdentityHit(IdentityOrigin.MAPS, "Distribuciones Irati", "Google Maps", address = "Pamplona")
        ))
        assertEquals("Distribuciones Irati", chosen?.name)
        assertEquals("Google Maps", chosen?.sourceLabel)
    }

    @Test fun appAvatarFillsTheGapBeforeTheWeb() {
        val chosen = chooseIdentity(listOf(
            IdentityHit(IdentityOrigin.WEB, "Resultado web", "Web"),
            IdentityHit(IdentityOrigin.APP, "Laura", "WhatsApp", photoUrl = "avatar")
        ))
        assertEquals(IdentityOrigin.APP, chosen?.origin)
    }

    @Test fun publicPhotoNeverOverwritesAnExistingContactPhoto() {
        assertFalse(maySaveContactPhoto(contactExists = true, alreadyHasPhoto = true, hasCandidate = true))
        assertTrue(maySaveContactPhoto(contactExists = true, alreadyHasPhoto = false, hasCandidate = true))
        assertFalse(maySaveContactPhoto(contactExists = false, alreadyHasPhoto = false, hasCandidate = true))
    }
}

class CartIdempotencyTest {
    @Test fun secondRunDoesNotWriteWhenQuantityAlreadyMatches() {
        val gateway = FakeBasket()
        gateway.lines["burrito"] = 2
        val cart = IdempotentCart(gateway)
        val first = cart.ensureProduct("burrito", 2)
        val second = cart.ensureProduct("burrito", 2)
        assertTrue(first is CartResult.Ready && !first.changed)
        assertTrue(second is CartResult.Ready && !second.changed)
        assertEquals(0, gateway.adds)
        assertEquals(0, gateway.sets)
        assertEquals(2, gateway.lines["burrito"])
    }

    @Test fun setsAbsoluteQuantityOnceThenReadsBack() {
        val gateway = FakeBasket()
        gateway.lines["pizza"] = 5
        val result = IdempotentCart(gateway).ensureProduct("pizza", 2)
        assertTrue(result is CartResult.Ready)
        assertEquals(2, gateway.lines["pizza"])
        assertEquals(1, gateway.sets)
        assertEquals(0, gateway.adds)
        assertTrue(gateway.quantitiesSent.all { it == 2 })
    }

    @Test fun missingItemIsAddedOnceWithTheFullQuantity() {
        val gateway = FakeBasket()
        val result = IdempotentCart(gateway).ensureProduct("burrito", 3)
        assertTrue(result is CartResult.Ready && result.changed)
        assertEquals(1, gateway.adds)
        assertEquals(listOf(3), gateway.addedQuantities)
        assertEquals(3, gateway.lines["burrito"])
        val again = IdempotentCart(gateway).ensureProduct("burrito", 3)
        assertTrue(again is CartResult.Ready && !again.changed)
        assertEquals(1, gateway.adds)
    }

    @Test fun aGatewayThatOnlyIncrementsCannotTripleTheItem() {
        val gateway = IncrementOnlyBasket()
        val result = IdempotentCart(gateway).ensureProduct("latas", 3)
        val stopped = result as CartResult.Stopped
        assertTrue(stopped.message.contains("parado") || stopped.message.contains("Paro") || stopped.message.contains("exacta"))
        assertTrue(gateway.adds <= 1)
        assertTrue(gateway.sets <= 1)
        assertTrue((gateway.lines["latas"] ?: 0) < 3)
    }

    @Test fun readFailureStopsBeforeAnyAdd() {
        val gateway = FakeBasket(failReads = 1)
        val result = IdempotentCart(gateway).ensureProduct("agua", 2)
        assertTrue(result is CartResult.Stopped)
        assertEquals(0, gateway.adds)
        assertEquals(0, gateway.sets)
    }

    @Test fun approvalSummaryListsItemsAndRefusesToPay() {
        val basket = Basket("glovo", "b1", listOf(BasketLine("1", "Burrito", 2, 850)), 1700)
        val card = ApprovalCard("glovo", basket, "Cesta de Glovo preparada.")
        val text = card.summary()
        assertTrue(text.contains("2 × Burrito"))
        assertTrue(text.contains("17,00 €"))
        assertTrue(text.contains("no paga"))
    }

    @Test fun payStaysBlockedWithoutAnExplicitYesAndAnOptIn() {
        val session = DeliverySession("glovo", "https://api.glovoapp.com", allowPay = true, placeOrderPath = "/pay")
        val basket = Basket("glovo", "b1", emptyList(), 0)
        assertTrue(payRequest(session, basket, explicitConfirmation = false) is PayResult.Blocked)
        assertTrue(payRequest(session.copy(allowPay = false), basket, true) is PayResult.Blocked)
        assertTrue(payRequest(session, basket, true) is PayResult.Submitted)
    }

    @Test fun glovoSetQuantitySendsTheAbsoluteNumberOnce() {
        val session = DeliverySession("glovo", "https://api.glovoapp.com", authorization = "Bearer user-token")
        val basket = Basket("glovo", "basket-9", listOf(BasketLine("sku", "Burrito", 1, null)), null)
        val spec = BasketJson.setQuantitySpec(session, basket, "sku", 4)
        assertEquals("PUT", spec.method)
        assertTrue(spec.url.endsWith("/v3/baskets/basket-9/products/sku"))
        assertTrue(spec.body!!.contains("\"quantity\":4"))
        assertEquals("Bearer user-token", spec.headers["Authorization"])
        val add = BasketJson.addOnceSpec(session, basket, "burrito", 4)
        assertEquals("POST", add.method)
        assertTrue(add.body!!.contains("\"quantity\":4"))
        assertFalse(add.body!!.contains("\"quantity\":1"))
    }

    @Test fun parserReadsBasketAndHttpGatewayReadsBackAfterSet() {
        val raw = """{"basketId":"b1","products":[{"id":"sku","name":"Burrito","quantity":1,"price":8.5}],"total":8.5}"""
        val parsed = BasketJson.parse("glovo", raw)!!
        assertEquals(1, parsed.quantityOf("sku"))
        assertEquals(850, parsed.totalCents)
        val updated = """{"basketId":"b1","products":[{"id":"sku","name":"Burrito","quantity":4,"price":8.5}],"total":34}"""
        val transport = ScriptTransport(mutableListOf(
            HttpResponse(200, raw),
            HttpResponse(200, raw),
            HttpResponse(200, "{}"),
            HttpResponse(200, updated)
        ))
        val gateway = HttpBasketGateway(DeliverySession("glovo", "https://api.glovoapp.com"), transport)
        val result = IdempotentCart(gateway).ensureProduct("Burrito", 4)
        assertTrue(result is CartResult.Ready)
        assertEquals(4, (result as CartResult.Ready).basket.quantityOf("sku"))
        assertEquals(1, transport.sent.count { it.method == "PUT" })
        assertTrue(transport.sent.none { it.url.contains("/pay") })
    }
}

class LoopGuardTest {
    @Test fun duplicateAddsStopAndAVerifiedQuantityDoesNotClickAgain() {
        val guard = CartLoopGuard(maxIdentical = 2)
        assertTrue(guard.decide("Añadir", observedQty = 1, targetQty = 2) is LoopDecision.Proceed)
        assertTrue(guard.decide("Añadir", observedQty = 1, targetQty = 2) is LoopDecision.Proceed)
        val stopped = guard.decide("Añadir", observedQty = null, targetQty = 2) as LoopDecision.Stop
        assertFalse(stopped.success)
        assertTrue(stopped.message.contains("duplicar"))

        val done = CartLoopGuard().decide("Añadir", observedQty = 2, targetQty = 2) as LoopDecision.Stop
        assertTrue(done.success)

        val over = CartLoopGuard().decide("+", observedQty = 3, targetQty = 1) as LoopDecision.Stop
        assertFalse(over.success)
        assertTrue(over.message.contains("triplicar") || over.message.contains("supera"))
    }

    @Test fun quantityIsReadFromTheVisibleRow() {
        val qty = CartUi.observeQuantity(listOf("Burrito", "cantidad 2", "Ver cesta"), "burrito")
        assertEquals(2, qty)
    }
}

class DeliveryParseTest {
    @Test fun parsesStoreItemAndQuantity() {
        val task = DeliveryTaskParser.parse("Añade 2 burritos en Glovo")
        assertEquals("glovo", task?.store)
        assertEquals(2, task?.quantity)
        assertEquals("burritos", task?.item)
        assertTrue("uber eats" in DeliveryTaskParser.apiStores)
        assertNull(DeliveryTaskParser.parse("abre ajustes"))
    }
}

private class FakeBasket(private var failReads: Int = 0) : BasketGateway {
    val lines = linkedMapOf<String, Int>()
    var adds = 0
    var sets = 0
    val quantitiesSent = mutableListOf<Int>()
    val addedQuantities = mutableListOf<Int>()

    override fun read(): Basket {
        if (failReads > 0) {
            failReads--
            throw IllegalStateException("lectura")
        }
        return snapshot()
    }

    override fun setQuantity(lineKey: String, quantity: Int) {
        sets++
        quantitiesSent += quantity
        lines[lineKey] = quantity
    }

    override fun addOnce(itemQuery: String, quantity: Int) {
        adds++
        addedQuantities += quantity
        lines[normalizeItem(itemQuery)] = quantity
    }

    private fun snapshot() = Basket(
        "glovo",
        "b1",
        lines.map { BasketLine(it.key, it.key, it.value, 100) },
        lines.values.sum() * 100
    )
}

/** Behaves like the old accessibility loop: every write only increments by one. */
private class IncrementOnlyBasket : BasketGateway {
    val lines = linkedMapOf<String, Int>()
    var adds = 0
    var sets = 0

    override fun read() = Basket("glovo", "b1", lines.map { BasketLine(it.key, it.key, it.value, null) }, null)

    override fun setQuantity(lineKey: String, quantity: Int) {
        sets++
        lines[lineKey] = (lines[lineKey] ?: 0) + 1
    }

    override fun addOnce(itemQuery: String, quantity: Int) {
        adds++
        val key = normalizeItem(itemQuery)
        lines[key] = (lines[key] ?: 0) + 1
    }
}

private class ScriptTransport(private val responses: MutableList<HttpResponse>) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    override fun exchange(request: HttpRequest): HttpResponse {
        sent += request
        if (responses.isEmpty()) return HttpResponse(500, "")
        return responses.removeAt(0)
    }
}
