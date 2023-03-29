package no.nav.k9punsj.pleiepengerlivetssluttfase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import no.nav.k9.kodeverk.dokument.Brevkode
import no.nav.k9.søknad.Søknad
import no.nav.k9.søknad.felles.Feil
import no.nav.k9punsj.akjonspunkter.AksjonspunktService
import no.nav.k9punsj.domenetjenester.MappeService
import no.nav.k9punsj.domenetjenester.PersonService
import no.nav.k9punsj.domenetjenester.SoknadService
import no.nav.k9punsj.felles.FagsakYtelseType
import no.nav.k9punsj.felles.dto.JournalposterDto
import no.nav.k9punsj.felles.dto.Matchfagsak
import no.nav.k9punsj.felles.dto.OpprettNySøknad
import no.nav.k9punsj.felles.dto.PeriodeDto
import no.nav.k9punsj.felles.dto.SendSøknad
import no.nav.k9punsj.felles.dto.SøknadFeil
import no.nav.k9punsj.felles.dto.hentUtJournalposter
import no.nav.k9punsj.integrasjoner.k9sak.HentK9SaksnummerGrunnlag
import no.nav.k9punsj.integrasjoner.k9sak.K9SakService
import no.nav.k9punsj.journalpost.JournalpostService
import no.nav.k9punsj.openapi.OasFeil
import no.nav.k9punsj.tilgangskontroll.azuregraph.IAzureGraphService
import no.nav.k9punsj.utils.ServerRequestUtils.søknadLocationUri
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.json

@Service
internal class PleiepengerLivetsSluttfaseService(
    private val objectMapper: ObjectMapper,
    private val personService: PersonService,
    private val mappeService: MappeService,
    private val azureGraphService: IAzureGraphService,
    private val journalpostService: JournalpostService,
    private val soknadService: SoknadService,
    private val k9SakService: K9SakService,
    private val aksjonspunktService: AksjonspunktService
) {

    private companion object {
        private val logger = LoggerFactory.getLogger(PleiepengerLivetsSluttfaseService::class.java)
    }

    internal suspend fun henteMappe(norskIdent: String): ServerResponse {
        val person = personService.finnPersonVedNorskIdent(norskIdent)
        if (person != null) {
            val svarDto = mappeService.hentMappe(
                person = person
            ).tilPlsVisning(norskIdent)
            return ServerResponse
                .ok()
                .json()
                .bodyValueAndAwait(svarDto)
        }
        return ServerResponse
            .ok()
            .json()
            .bodyValueAndAwait(SvarPlsDto(norskIdent, FagsakYtelseType.PLEIEPENGER_LIVETS_SLUTTFASE.kode, listOf()))
    }

    internal suspend fun henteSøknad(søknadId: String): ServerResponse {
        val søknad = mappeService.hentSøknad(søknadId)
            ?: return ServerResponse.notFound().buildAndAwait()

        return ServerResponse
            .ok()
            .json()
            .bodyValueAndAwait(søknad.tilPlsvisning())
    }

    internal suspend fun oppdaterEksisterendeSøknad(søknad: PleiepengerLivetsSluttfaseSøknadDto): ServerResponse {
        val saksbehandler = azureGraphService.hentIdentTilInnloggetBruker()

        val søknadEntitet = mappeService.utfyllendeInnsendingPls(
            dto = søknad,
            saksbehandler = saksbehandler
        ) ?: return ServerResponse.badRequest().buildAndAwait()

        val (entitet, _) = søknadEntitet
        val søker = personService.finnPerson(entitet.søkerId)
        journalpostService.settKildeHvisIkkeFinnesFraFør(
            hentUtJournalposter(entitet),
            søker.aktørId
        )
        return ServerResponse
            .ok()
            .json()
            .bodyValueAndAwait(søknad)
    }

    internal suspend fun sendEksisterendeSøknad(sendSøknad: SendSøknad): ServerResponse {
        val søknadEntitet = mappeService.hentSøknad(sendSøknad.soeknadId)
            ?: return ServerResponse.badRequest().buildAndAwait()

        try {
            val søknad: PleiepengerLivetsSluttfaseSøknadDto =
                objectMapper.convertValue(søknadEntitet.søknad!!)

            val eksisterendePerioderFraK9Sak = henterPerioderSomFinnesIK9sak(søknad)?.first ?: emptyList()

            val journalPoster = søknadEntitet.journalposter!!
            val journalposterDto: JournalposterDto = objectMapper.convertValue(journalPoster)
            val journalpostIder = journalposterDto.journalposter.filter { journalpostId ->
                journalpostService.kanSendeInn(listOf(journalpostId)).also { kanSendesInn ->
                    if (!kanSendesInn) {
                        logger.warn("JournalpostId $journalpostId kan ikke sendes inn. Filtreres bort fra innsendingen.")
                    }
                }
            }.toMutableSet()

            if (journalpostIder.isEmpty()) {
                logger.error("Innsendingen må inneholde minst en journalpost som kan sendes inn.")
                return ServerResponse
                    .status(HttpStatus.CONFLICT)
                    .bodyValueAndAwait(OasFeil("Innsendingen må inneholde minst en journalpost som kan sendes inn."))
            }

            val (søknadK9Format, feilISøknaden) = MapPlsfTilK9Format(
                søknadId = søknad.soeknadId,
                journalpostIder = journalpostIder,
                dto = søknad,
                perioderSomFinnesIK9 = eksisterendePerioderFraK9Sak
            ).søknadOgFeil()

            if (feilISøknaden.isNotEmpty()) {
                val feil = feilISøknaden.map { feil ->
                    SøknadFeil.SøknadFeilDto(
                        feil.felt,
                        feil.feilkode,
                        feil.feilmelding
                    )
                }.toList()

                return ServerResponse
                    .status(HttpStatus.BAD_REQUEST)
                    .json()
                    .bodyValueAndAwait(SøknadFeil(sendSøknad.soeknadId, feil))
            }

            val feil = soknadService.sendSøknad(
                søknad = søknadK9Format,
                brevkode = Brevkode.SØKNAD_PLEIEPENGER_LIVETS_SLUTTFASE,
                journalpostIder = journalpostIder
            )
            if (feil != null) {
                val (httpStatus, feilen) = feil

                return ServerResponse
                    .status(httpStatus)
                    .json()
                    .bodyValueAndAwait(OasFeil(feilen))
            }

            val ansvarligSaksbehandler = soknadService.hentSistEndretAvSaksbehandler(søknad.soeknadId)
            aksjonspunktService.settUtførtPåAltSendLukkOppgaveTilK9Los(
                journalpostId = journalpostIder,
                erSendtInn = true,
                ansvarligSaksbehandler = ansvarligSaksbehandler
            )

            return ServerResponse
                .accepted()
                .json()
                .bodyValueAndAwait(søknadK9Format)
        } catch (e: Exception) {
            return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .json()
                .bodyValueAndAwait(e.localizedMessage)
        }
    }

    internal suspend fun nySøknad(request: ServerRequest, opprettNySøknad: OpprettNySøknad): ServerResponse {
        // oppretter sak i k9-sak hvis det ikke finnes fra før
        if (opprettNySøknad.pleietrengendeIdent != null) {
            val hentK9SaksnummerGrunnlag = HentK9SaksnummerGrunnlag(
                søknadstype = FagsakYtelseType.PLEIEPENGER_LIVETS_SLUTTFASE,
                annenPart = null,
                søker = opprettNySøknad.norskIdent,
                pleietrengende = opprettNySøknad.pleietrengendeIdent,
                journalpostId = opprettNySøknad.journalpostId
            )

            val (_, feil) = k9SakService.hentEllerOpprettSaksnummer(
                k9SaksnummerGrunnlag = hentK9SaksnummerGrunnlag
            )

            if(feil != null) {
                return ServerResponse
                    .badRequest()
                    .json()
                    .bodyValueAndAwait(feil)
            }
        }

        // setter riktig type der man jobber på en ukjent i utgangspunktet
        journalpostService.settFagsakYtelseType(
            FagsakYtelseType.PLEIEPENGER_LIVETS_SLUTTFASE,
            opprettNySøknad.journalpostId
        )

        val søknadEntitet = mappeService.forsteInnsendingPls(
            nySøknad = opprettNySøknad
        )
        return ServerResponse
            .created(request.søknadLocationUri(søknadEntitet.søknadId))
            .json()
            .bodyValueAndAwait(søknadEntitet.tilPlsvisning())
    }

    internal suspend fun validerSøknad(soknadTilValidering: PleiepengerLivetsSluttfaseSøknadDto): ServerResponse {
        val eksisterendePerioderFraK9Sak = henterPerioderSomFinnesIK9sak(soknadTilValidering)
            ?.first ?: emptyList()
        val søknadEntitet = mappeService.hentSøknad(soknadTilValidering.soeknadId)
            ?: return ServerResponse
                .badRequest()
                .buildAndAwait()

        val journalPoster = søknadEntitet.journalposter!!
        val journalposterDto: JournalposterDto = objectMapper.convertValue(journalPoster)

        val mapTilEksternFormat: Pair<Søknad, List<Feil>>?

        try {
            mapTilEksternFormat = MapPlsfTilK9Format(
                søknadId = soknadTilValidering.soeknadId,
                journalpostIder = journalposterDto.journalposter,
                dto = soknadTilValidering,
                perioderSomFinnesIK9 = eksisterendePerioderFraK9Sak
            ).søknadOgFeil()
        } catch (e: Exception) {
            return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .json()
                .bodyValueAndAwait(e.localizedMessage)
        }

        val (søknad, feilListe) = mapTilEksternFormat
        if (feilListe.isNotEmpty()) {
            val feil = feilListe.map { feil ->
                SøknadFeil.SøknadFeilDto(
                    feil.felt,
                    feil.feilkode,
                    feil.feilmelding
                )
            }.toList()

            return ServerResponse
                .status(HttpStatus.BAD_REQUEST)
                .json()
                .bodyValueAndAwait(SøknadFeil(soknadTilValidering.soeknadId, feil))
        }
        val saksbehandler = azureGraphService.hentIdentTilInnloggetBruker()
        mappeService.utfyllendeInnsendingPls(
            dto = soknadTilValidering,
            saksbehandler = saksbehandler
        )
        return ServerResponse
            .status(HttpStatus.ACCEPTED)
            .json()
            .bodyValueAndAwait(søknad)
    }

    internal suspend fun hentInfoFraK9Sak(matchfagsak: Matchfagsak): ServerResponse {
        val (perioder, _) = k9SakService.hentPerioderSomFinnesIK9(
            søker = matchfagsak.brukerIdent,
            barn = matchfagsak.barnIdent,
            fagsakYtelseType = FagsakYtelseType.PLEIEPENGER_LIVETS_SLUTTFASE
        )

        return if (perioder == null) {
            return ServerResponse
                .ok()
                .json()
                .bodyValueAndAwait(listOf<PeriodeDto>())
        } else {
            ServerResponse
                .ok()
                .json()
                .bodyValueAndAwait(perioder)
        }
    }

    private suspend fun henterPerioderSomFinnesIK9sak(dto: PleiepengerLivetsSluttfaseSøknadDto): Pair<List<PeriodeDto>?, String?>? {
        if (dto.soekerId.isNullOrBlank() || dto.pleietrengende == null || dto.pleietrengende.norskIdent.isNullOrBlank()) {
            return null
        }
        return k9SakService.hentPerioderSomFinnesIK9(
            søker = dto.soekerId,
            barn = dto.pleietrengende.norskIdent,
            fagsakYtelseType = FagsakYtelseType.PLEIEPENGER_LIVETS_SLUTTFASE
        )
    }
}
