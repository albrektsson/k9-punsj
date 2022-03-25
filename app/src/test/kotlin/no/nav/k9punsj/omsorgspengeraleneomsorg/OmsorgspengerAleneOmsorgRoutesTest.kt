package no.nav.k9punsj.omsorgspengeraleneomsorg

import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.runBlocking
import no.nav.helse.dusseldorf.testsupport.jws.Azure
import no.nav.k9punsj.TestSetup
import no.nav.k9punsj.db.datamodell.FagsakYtelseTypeUri
import no.nav.k9punsj.domenetjenester.dto.NorskIdentDto
import no.nav.k9punsj.domenetjenester.dto.OpprettNySøknad
import no.nav.k9punsj.domenetjenester.dto.SendSøknad
import no.nav.k9punsj.domenetjenester.dto.SøknadIdDto
import no.nav.k9punsj.openapi.OasSoknadsfeil
import no.nav.k9punsj.util.*
import no.nav.k9punsj.util.WebClientUtils.awaitBodyWithType
import no.nav.k9punsj.util.WebClientUtils.getAndAssert
import no.nav.k9punsj.util.WebClientUtils.postAndAssert
import no.nav.k9punsj.util.WebClientUtils.postAndAssertAwaitWithStatusAndBody
import no.nav.k9punsj.util.WebClientUtils.putAndAssert
import no.nav.k9punsj.wiremock.saksbehandlerAccessToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.reactive.function.BodyInserters
import java.net.URI
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

@ExtendWith(SpringExtension::class, MockKExtension::class)
internal class OmsorgspengerAleneOmsorgRoutesTest{
    private val client = TestSetup.client
    private val api = "api"
    private val søknadTypeUri = FagsakYtelseTypeUri.OMSORGSPENGER_ALENE_OM_OMSORGEN
    private val saksbehandlerAuthorizationHeader = "Bearer ${Azure.V2_0.saksbehandlerAccessToken()}"
    private val journalpostRepository = DatabaseUtil.getJournalpostRepo()


    @AfterEach
    internal fun tearDown() {
        DatabaseUtil.cleanDB()
    }

    @Test
    fun `Får tom liste når personen ikke har en eksisterende mappe`(): Unit = runBlocking {
        val norskIdent = "01110050053"
        val body = client.getAndAssert<SvarOmsAODto>(
            norskIdent = norskIdent,
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.OK,
            api, søknadTypeUri, "mappe"
        )
        Assertions.assertTrue(body.søknader!!.isEmpty())
    }

    @Test
    fun `Opprette ny mappe på person`(): Unit = runBlocking {
        val norskIdent = "01010050053"
        val pleietrengende = "01010050023"
        val opprettNySøknad = opprettSøknad(norskIdent, UUID.randomUUID().toString(), pleietrengende)

        client.postAndAssert(
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.CREATED,
            requestBody = BodyInserters.fromValue(opprettNySøknad),
            api, søknadTypeUri
        )
    }

    @Test
    fun `Skal verifisere at søknad er ok`(): Unit = runBlocking {
        val norskIdent = "02022352122"
        val pleietrengende = "01010050023"
        val soeknad: SøknadJson = LesFraFilUtil.søknadFraFrontendOmsAO()
        val journalpostid = abs(Random(234234).nextInt()).toString()
        tilpasserSøknadsMalTilTesten(soeknad, norskIdent, journalpostid)
        opprettOgLagreSoeknad(soeknadJson = soeknad, ident = norskIdent, journalpostid, pleietrengende)

        val body = client.postAndAssertAwaitWithStatusAndBody<SøknadJson, OasSoknadsfeil>(
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.ACCEPTED,
            requestBody = BodyInserters.fromValue(soeknad),
            api, søknadTypeUri, "valider"
        )
        org.assertj.core.api.Assertions.assertThat(body.feil).isNull()
    }

    @Test
    fun `Prøver å sende søknaden til Kafka når den er gyldig`(): Unit = runBlocking {
        val norskIdent = "02020050123"
        val pleietrengende = "01010050023"
        val gyldigSoeknad: SøknadJson = LesFraFilUtil.søknadFraFrontendOmsAO()
        val journalpostid = abs(Random(56234).nextInt()).toString()
        tilpasserSøknadsMalTilTesten(gyldigSoeknad, norskIdent, journalpostid)

        val body = opprettOgSendInnSoeknad(soeknadJson = gyldigSoeknad, ident = norskIdent, journalpostid, pleietrengende)
        org.assertj.core.api.Assertions.assertThat(body.feil).isNull()
        org.assertj.core.api.Assertions.assertThat(journalpostRepository.kanSendeInn(listOf(journalpostid))).isFalse
    }

    @Test
    fun `Skal sjekke mapping av felter`(): Unit = runBlocking {
        val norskIdent = "02020050123"
        val pleietrengende = "01010050023"
        val gyldigSoeknad: SøknadJson = LesFraFilUtil.søknadFraFrontendOmsAO()
        val journalpostid = abs(Random(56234).nextInt()).toString()
        tilpasserSøknadsMalTilTesten(gyldigSoeknad, norskIdent, journalpostid)

        val søknad = opprettOgLagreSoeknad(soeknadJson = gyldigSoeknad, ident = norskIdent, journalpostid, pleietrengende)

        val søknadViaGet = client.get()
            .uri { it.pathSegment(api, søknadTypeUri, "mappe", søknad.soeknadId).build() }
            .header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .awaitBodyWithType<OmsorgspengerAleneOmsorgSøknadDto>()

        org.assertj.core.api.Assertions.assertThat(søknadViaGet.begrunnelseForInnsending).isEqualTo("JEG VET IKKE")
        org.assertj.core.api.Assertions.assertThat(søknadViaGet.barn?.foedselsdato).isEqualTo(LocalDate.of(2018,10, 30))
    }

    private fun opprettSøknad(
        personnummer: NorskIdentDto,
        journalpostId: String,
        pleietrengende: String,
    ): OpprettNySøknad {
        return OpprettNySøknad(personnummer, journalpostId, pleietrengende, null, null)
    }

    private fun tilpasserSøknadsMalTilTesten(
        søknad: MutableMap<String, Any?>,
        norskIdent: String,
        journalpostId: String? = null,
    ) {
        søknad.replace("soekerId", norskIdent)
        if (journalpostId != null) søknad.replace("journalposter", arrayOf(journalpostId))
    }

    private suspend fun opprettOgLagreSoeknad(
        soeknadJson: SøknadJson,
        ident: String,
        journalpostid: String = IdGenerator.nesteId(),
        pleietrengende: String,
    ): OmsorgspengerAleneOmsorgSøknadDto {
        val innsendingForOpprettelseAvMappe = opprettSøknad(ident, journalpostid, pleietrengende)

        // oppretter en søknad
        val resPost = client.postAndAssert(
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.CREATED,
            requestBody = BodyInserters.fromValue(innsendingForOpprettelseAvMappe),
            api, søknadTypeUri
        )

        val location = resPost.headers().asHttpHeaders().location
        Assertions.assertNotNull(location)

        leggerPåNySøknadId(soeknadJson, location)

        // fyller ut en søknad
        val søknadDtoFyltUt = client.putAndAssert<SøknadJson, OmsorgspengerAleneOmsorgSøknadDto>(
            norskIdent = null,
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.OK,
            requestBody = BodyInserters.fromValue(soeknadJson),
            api, søknadTypeUri, "oppdater"
        )

        Assertions.assertNotNull(søknadDtoFyltUt.soekerId)
        return søknadDtoFyltUt
    }

    private fun leggerPåNySøknadId(søknadFraFrontend: MutableMap<String, Any?>, location: URI?) {
        val path = location?.path
        val søknadId = path?.substring(path.lastIndexOf('/'))
        val trim = søknadId?.trim('/')
        søknadFraFrontend.replace("soeknadId", trim)
    }

    private suspend fun opprettOgSendInnSoeknad(
        soeknadJson: SøknadJson,
        ident: String,
        journalpostid: String = IdGenerator.nesteId(),
        pleietrengende: String,
    ): OasSoknadsfeil {
        val innsendingForOpprettelseAvMappe = opprettSøknad(ident, journalpostid, pleietrengende)

        // oppretter en søknad
        val response = client.postAndAssert(
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.CREATED,
            requestBody = BodyInserters.fromValue(innsendingForOpprettelseAvMappe),
            api, søknadTypeUri
        )

        val location = response.headers().asHttpHeaders().location
        Assertions.assertEquals(HttpStatus.CREATED, response.statusCode())
        Assertions.assertNotNull(location)

        leggerPåNySøknadId(soeknadJson, location)

        // fyller ut en søknad
        val søknadDtoFyltUt: OmsorgspengerAleneOmsorgSøknadDto = client.putAndAssert(
            norskIdent = null,
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.OK,
            requestBody = BodyInserters.fromValue(soeknadJson),
            api, søknadTypeUri, "oppdater"
        )

        Assertions.assertNotNull(søknadDtoFyltUt.soekerId)

        val søknadId = søknadDtoFyltUt.soeknadId
        val sendSøknad = lagSendSøknad(norskIdent = ident, søknadId = søknadId)

        val journalposter = søknadDtoFyltUt.journalposter!!

        val kanSendeInn = journalpostRepository.kanSendeInn(journalposter)
        org.assertj.core.api.Assertions.assertThat(kanSendeInn).isTrue

        // sender en søknad
        val body = client.postAndAssertAwaitWithStatusAndBody<SendSøknad, OasSoknadsfeil>(
            authorizationHeader = saksbehandlerAuthorizationHeader,
            assertStatus = HttpStatus.ACCEPTED,
            requestBody = BodyInserters.fromValue(sendSøknad),
            api, søknadTypeUri, "send"
        )

        return body
    }

    private fun lagSendSøknad(
        norskIdent: NorskIdentDto,
        søknadId: SøknadIdDto,
    ): SendSøknad {
        return SendSøknad(norskIdent, søknadId)
    }
}