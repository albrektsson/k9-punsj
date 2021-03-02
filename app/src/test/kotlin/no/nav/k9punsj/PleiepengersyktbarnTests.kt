package no.nav.k9punsj

import com.fasterxml.jackson.module.kotlin.convertValue
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.runBlocking
import no.nav.helse.dusseldorf.testsupport.jws.Azure
import no.nav.k9punsj.db.datamodell.FagsakYtelseTypeUri
import no.nav.k9punsj.domenetjenester.mappers.SøknadMapper
import no.nav.k9punsj.rest.web.HentSøknad
import no.nav.k9punsj.rest.web.Innsending
import no.nav.k9punsj.rest.web.SendSøknad
import no.nav.k9punsj.rest.web.SøknadJson
import no.nav.k9punsj.rest.web.dto.*
import no.nav.k9punsj.rest.web.openapi.OasPleiepengerSyktBarnFeil
import no.nav.k9punsj.util.LesFraFilUtil
import no.nav.k9punsj.wiremock.saksbehandlerAccessToken
import org.junit.Assert.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import java.time.LocalDate

@ExtendWith(SpringExtension::class, MockKExtension::class)
class PleiepengersyktbarnTests {

    private val client = TestSetup.client
    private val api = "api"
    private val søknadTypeUri = FagsakYtelseTypeUri.PLEIEPENGER_SYKT_BARN
    private val saksbehandlerAuthorizationHeader = "Bearer ${Azure.V2_0.saksbehandlerAccessToken()}"

    @Test
    fun `Får tom liste når personen ikke har en eksisterende mappe`() {
        val norskIdent = "01110050053"
        val res = client.get()
            .uri { it.pathSegment(api, søknadTypeUri, "mappe").build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .header("X-Nav-NorskIdent", norskIdent)
            .awaitExchangeBlocking()
        assertEquals(HttpStatus.OK, res.statusCode())
        val svar = runBlocking { res.awaitBody<SvarDto<PleiepengerSøknadVisningDto>>() }
        assertTrue(svar.søknader!!.isEmpty())
    }

    @Test
    fun `Opprette ny mappe på person`() {
        val norskIdent = "01010050053"
        val innsending = lagInnsending(norskIdent, "999")
        val res = client.post()
            .uri { it.pathSegment(api, søknadTypeUri).build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsending))
            .awaitExchangeBlocking()
        assertEquals(HttpStatus.CREATED, res.statusCode())
    }

    @Test
    fun `Hente eksisterende mappe på person`() {
        val norskIdent = "02020050163"
        val innsending = lagInnsending(norskIdent, "9999")

        val resPost = client.post()
            .uri { it.pathSegment(api, søknadTypeUri).build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsending))
            .awaitExchangeBlocking()
        assertEquals(HttpStatus.CREATED, resPost.statusCode())

        val res = client.get()
            .uri { it.pathSegment(api, søknadTypeUri, "mappe").build() }
            .header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .header("X-Nav-NorskIdent", norskIdent)
            .awaitExchangeBlocking()
        assertEquals(HttpStatus.OK, res.statusCode())

        val mappeSvar = runBlocking { res.awaitBody<SvarDto<PleiepengerSøknadVisningDto>>() }
        val journalposterDto = mappeSvar.søknader?.first()?.journalposter
        assertEquals("9999", journalposterDto?.journalposter?.first())
    }

    @Test
    fun `Oppdaterer en søknad`() {
        val søknad = LesFraFilUtil.genererKomplettSøknad()
        val norskIdent = "02030050163"
        endreSøkerIGenererSøknad(søknad, norskIdent)

        val journalpostid = "21707da8-a13b-4927-8776-c53399727b29"
        val innsendingForOpprettelseAvMappeOgTomSøknad = lagInnsending(norskIdent, journalpostid)

        val resPost = client.post()
            .uri { it.pathSegment(api, søknadTypeUri).build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsendingForOpprettelseAvMappeOgTomSøknad))
            .awaitExchangeBlocking()

        val søknadDto = runBlocking { resPost.awaitBody<SøknadDto<PleiepengerSøknadVisningDto>>() }
        assertNotNull(søknadDto)

        val søknadPåRiktigFormat = søknadPåRiktigFormat(søknad)
        val innsendingForOppdateringAvSoeknad = lagInnsending(norskIdent, journalpostid, søknadPåRiktigFormat, søknadDto.søknadId)

        val res = client.put()
            .uri { it.pathSegment(api, søknadTypeUri, "oppdater").build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsendingForOppdateringAvSoeknad))
            .awaitExchangeBlocking()

        val oppdatertSoeknadDto = runBlocking { res.awaitBody<SøknadOppdaterDto<PleiepengerSøknadVisningDto>>() }
        val pleiepengerSøknadVisningDto = oppdatertSoeknadDto.søknad

        assertNotNull(pleiepengerSøknadVisningDto)
        assertEquals(norskIdent, pleiepengerSøknadVisningDto.søker?.norskIdentitetsnummer)
        assertEquals(PeriodeDto(
            LocalDate.of(2018, 12, 30),
            LocalDate.of(2019, 10, 20)),
            pleiepengerSøknadVisningDto.ytelse?.søknadsperiode)
        assertEquals(HttpStatus.OK, res.statusCode())
    }

    private fun søknadPåRiktigFormat(søknad: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val convertValue = objectMapper().convertValue<PleiepengerSøknadMottakDto>(søknad)
        val maped = SøknadMapper.mapTilVisningFormat(convertValue)
        return objectMapper().convertValue(maped)
    }

    @Test
    fun `Innsending av søknad returnerer 404 når mappe ikke finnes`() {
        val norskIdent = "12030050163"
        val søknadId = "d8e2c5a8-b993-4d2d-9cb5-fdb22a653a0c"

        val sendSøknad = lagSendSøknad(norskIdent = norskIdent, søknadId = søknadId)

        val res = client.post()
            .uri { it.pathSegment(api, søknadTypeUri, "send").build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(sendSøknad))
            .awaitExchangeBlocking()

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode())
    }

    @Test
    fun `Prøver å sende søknaden til Kafka når den er gyldig`() {
        val norskIdent = "02020050121"
        val gyldigSoeknad: SøknadJson = LesFraFilUtil.genererKomplettSøknad()
        endreSøkerIGenererSøknad(gyldigSoeknad, norskIdent)

        val res = opprettOgSendInnSoeknad(soeknadJson = lagSøknadPåRiktigFormat(gyldigSoeknad), ident = norskIdent)

        assertEquals(HttpStatus.ACCEPTED, res.statusCode())
    }

    private fun lagSøknadPåRiktigFormat(gyldigSoeknad: SøknadJson): SøknadJson {
        val mottatDto = objectMapper().convertValue<PleiepengerSøknadMottakDto>(gyldigSoeknad)
        val formatFraFrontend = SøknadMapper.mapTilVisningFormat(mottatDto)
        return objectMapper().convertValue(formatFraFrontend)
    }

    @Test
    fun `Skal fange opp feilen overlappendePerioder i søknaden`() {
        val norskIdent = "02020052121"
        val gyldigSoeknad: SøknadJson = LesFraFilUtil.genererSøknadMedFeil()
        endreSøkerIGenererSøknad(gyldigSoeknad, norskIdent)

        val res = opprettOgSendInnSoeknad(soeknadJson = lagSøknadPåRiktigFormat(gyldigSoeknad), ident = norskIdent)

        val response = res
            .bodyToMono(OasPleiepengerSyktBarnFeil::class.java)
            .block()

        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode())
        assertEquals("overlappendePerioder", response?.feil?.first()?.feilkode!!)
    }

    @Test
    fun `Skal hente komplett søknad fra k9-sak`() {
        val søknad = LesFraFilUtil.genererKomplettSøknad()
        val norskIdent = (søknad["søker"] as Map<*, *>)["norskIdentitetsnummer"] as String
        val hentSøknad = lagHentSøknad(norskIdent,
            PeriodeDto(LocalDate.of(2018, 12, 30), LocalDate.of(2019, 10, 20)))

        val res = client.post()
            .uri { it.pathSegment(api, "k9-sak", søknadTypeUri).build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(hentSøknad))
            .awaitExchangeBlocking()

        val søknadDto = runBlocking { res.awaitBody<SvarDto<PleiepengerSøknadVisningDto>>() }

        assertEquals(HttpStatus.OK, res.statusCode())
        assertEquals(søknadDto.søker, norskIdent)
        assertEquals(søknadDto.fagsakTypeKode, "PSB")
        assertTrue(søknadDto.søknader?.size == 1)
        assertTrue(søknadDto.søknader?.get(0)?.søknadId.isNullOrBlank().not())
        assertEquals(søknadDto.søknader?.get(0)?.søknad?.ytelse?.søknadsperiode?.fom, LocalDate.of(2018, 12,30))
        assertEquals(søknadDto.søknader?.get(0)?.søknad?.ytelse?.søknadsperiode?.tom, LocalDate.of(2019, 10,20))
    }

    @Test
    fun `Innsending av søknad med feil i perioden blir stoppet`() {
        val norskIdent = "02022352121"
        val gyldigSoeknad: SøknadJson = LesFraFilUtil.genererKomplettSøknad()
        endreSøkerIGenererSøknad(gyldigSoeknad, norskIdent)

        val ytelse = gyldigSoeknad["ytelse"] as MutableMap<String, Any>

        //ødelegger perioden
        ytelse.replace("søknadsperiode", "2019-12-30/2018-10-20")
        gyldigSoeknad.replace("ytelse", ytelse)

        val res = opprettOgSendInnSoeknad(soeknadJson = lagSøknadPåRiktigFormat(gyldigSoeknad), ident = norskIdent)

        val response = res
            .bodyToMono(OasPleiepengerSyktBarnFeil::class.java)
            .block()
        assertEquals(HttpStatus.BAD_REQUEST, res.statusCode())
        assertEquals("ugyldigPeriode", response?.feil?.first()?.feilkode!!)
    }

    private fun opprettOgSendInnSoeknad(
        soeknadJson: SøknadJson,
        ident: String,
        journalpostid: String = "73369b5b-d50e-47ab-8fc2-31ef35a71993",
    ): ClientResponse {
        val innsendingForOpprettelseAvMappe = lagInnsending(ident, journalpostid)

        // oppretter en søknad
        val resPost = client.post()
            .uri { it.pathSegment(api, søknadTypeUri).build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsendingForOpprettelseAvMappe))
            .awaitExchangeBlocking()

        val søknadDto = runBlocking { resPost.awaitBody<SøknadDto<PleiepengerSøknadVisningDto>>() }
        assertNotNull(søknadDto)

        // fyller ut en søknad
        val innsendingForUtfyllingAvSøknad = lagInnsending(ident, journalpostid, soeknadJson, søknadDto.søknadId)
        val resPut = client.put()
            .uri { it.pathSegment(api, søknadTypeUri, "oppdater").build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(innsendingForUtfyllingAvSøknad))
            .awaitExchangeBlocking()

        val søknadDtoFyltUt = runBlocking { resPut.awaitBody<SøknadOppdaterDto<PleiepengerSøknadVisningDto>>() }
        assertNotNull(søknadDtoFyltUt.søknad.søker)

        val søknadId = søknadDto.søknadId
        val sendSøknad = lagSendSøknad(norskIdent = ident, søknadId = søknadId)

        // sender en søknad
        return client.post()
            .uri { it.pathSegment(api, søknadTypeUri, "send").build() }.header(HttpHeaders.AUTHORIZATION, saksbehandlerAuthorizationHeader)
            .body(BodyInserters.fromValue(sendSøknad))
            .awaitExchangeBlocking()
    }
}

private fun WebClient.RequestHeadersSpec<*>.awaitExchangeBlocking(): ClientResponse = runBlocking { awaitExchange() }

private fun lagInnsending(
    personnummer: NorskIdentDto,
    journalpostId: String,
    søknad: SøknadJson = mutableMapOf(),
    søknadId: String? = null
): Innsending {
    return Innsending(personnummer, journalpostId, søknad, søknadId)
}

private fun lagSendSøknad(
    norskIdent: NorskIdentDto,
    søknadId: SøknadIdDto
) : SendSøknad{
    return SendSøknad(norskIdent, søknadId)
}

private fun lagHentSøknad(norskIdentDto: NorskIdentDto, periode: PeriodeDto): HentSøknad {
    return HentSøknad(norskIdent = norskIdentDto, periode = periode)
}

private fun endreSøkerIGenererSøknad(
    søknad: MutableMap<String, Any?>,
    norskIdent: String,
) {
    val norskIdentMap = søknad["søker"] as MutableMap<String, Any>
    norskIdentMap.replace("norskIdentitetsnummer", norskIdent)
    søknad.replace("søker", norskIdentMap)
}
