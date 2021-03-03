package no.nav.k9punsj.domenetjenester

import com.fasterxml.jackson.module.kotlin.convertValue
import no.nav.k9punsj.db.datamodell.*
import no.nav.k9punsj.db.repository.BunkeRepository
import no.nav.k9punsj.db.repository.MappeRepository
import no.nav.k9punsj.db.repository.SøknadRepository
import no.nav.k9punsj.objectMapper
import no.nav.k9punsj.rest.web.Innsending
import no.nav.k9punsj.rest.web.OpprettNySøknad
import no.nav.k9punsj.rest.web.dto.PleiepengerSøknadVisningDto
import no.nav.k9punsj.rest.web.dto.SøknadIdDto
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MappeService(
    val mappeRepository: MappeRepository,
    val søknadRepository: SøknadRepository,
    val bunkeRepository: BunkeRepository,
    val personService: PersonService,
) {

    suspend fun hent(mappeId: MappeId): Mappe? {
        val hentEierAvMappe = mappeRepository.hentEierAvMappe(mappeId)
        if (hentEierAvMappe != null) {
            return henterMappeMedAlleKoblinger(mappeId,
                personService.finnPerson(hentEierAvMappe))

        }
        return null
    }

    suspend fun hentMappe(person: Person, søknadType: FagsakYtelseType): Mappe {
        return henterMappeMedAlleKoblinger(mappeRepository.opprettEllerHentMappeForPerson(person.personId), person)
    }


    suspend fun førsteInnsending(søknadType: FagsakYtelseType, nySøknad: OpprettNySøknad): SøknadEntitet {
        val norskIdent = nySøknad.norskIdent
        val søker = personService.finnEllerOpprettPersonVedNorskIdent(norskIdent)

        val mappeId = mappeRepository.opprettEllerHentMappeForPerson(søker.personId)
        val bunkeId = bunkeRepository.opprettEllerHentBunkeForFagsakType(mappeId, søknadType)
        val søknadId = UUID.randomUUID()

        val journalposter = mutableMapOf<String, Any?>()
        journalposter["journalposter"] = listOf(nySøknad.journalpostId)


        //TODO(OJR) skal jeg legge på informasjon om hvilken saksbehandler som punsjet denne?
        val søknadEntitet = SøknadEntitet(
            søknadId = søknadId.toString(),
            bunkeId = bunkeId,
            søkerId = søker.personId,
            journalposter = journalposter
        )

        return søknadRepository.opprettSøknad(søknadEntitet)
    }

    suspend fun utfyllendeInnsending(innsending: Innsending, saksbehandler: String): Pair<SøknadEntitet, PleiepengerSøknadVisningDto>? {
        val hentSøknad = søknadRepository.hentSøknad(innsending.soeknadId)!!

        if (hentSøknad.sendtInn.not()) {
            val søknad = innsending.soeknad
            val journalposter = mutableMapOf<String, Any?>()
            journalposter["journalposter"] = listOf(innsending.journalpostId)
            val oppdatertSøknad =
                hentSøknad.copy(søknad = søknad, journalposter = journalposter, endret_av = saksbehandler)
            søknadRepository.oppdaterSøknad(oppdatertSøknad)

            val visningDto = objectMapper().convertValue<PleiepengerSøknadVisningDto>(søknad)
            return Pair(oppdatertSøknad, visningDto)
        } else {
            throw IllegalStateException("Kan ikke endre på en søknad som er sendt inn")
        }
    }

    private suspend fun henterMappeMedAlleKoblinger(
        mappeId: MappeId?,
        søker: Person,
        søknadId: SøknadId? = null,
    ): Mappe {
        val alleBunker = bunkeRepository.hentAlleBunkerForMappe(mappeId!!)
        val bunkerId = alleBunker.map { b -> b.bunkeId }.toList()
        val hentAlleSøknaderForBunker = bunkerId.flatMap { id -> søknadRepository.hentAlleSøknaderForBunker(id) }

        if (søknadId != null) {
            val harFeilet = hentAlleSøknaderForBunker.map { s -> s.søknadId }.contains(søknadId).not()
            if (harFeilet) {
                throw IllegalStateException("klarte ikke hente opp nettopp berørt søknad")
            }
        }
        val bunkerMedSøknader = alleBunker.map { b ->
            BunkeEntitet(b.bunkeId,
                b.fagsakYtelseType,
                hentAlleSøknaderForBunker.filter { s -> s.bunkeId == b.bunkeId }.toList())
        }
        return Mappe(mappeId, søker, bunkerMedSøknader)
    }

    suspend fun opprettTomSøknad(norskIdent: NorskIdent, type: FagsakYtelseType): SøknadId {
        val søker = personService.finnEllerOpprettPersonVedNorskIdent(norskIdent)
        val mappeId = mappeRepository.opprettEllerHentMappeForPerson(søker.personId)
        val bunkeId = bunkeRepository.opprettEllerHentBunkeForFagsakType(mappeId, type)
        val søknadId = UUID.randomUUID()

        val tomSøknad = SøknadEntitet(
            søknadId = søknadId.toString(),
            bunkeId = bunkeId,
            søkerId = søker.personId
        )

        val søknad = søknadRepository.opprettSøknad(tomSøknad)

        return søknad.søknadId
    }

    suspend fun hentSøknad(søknad: SøknadIdDto): SøknadEntitet? {
        return søknadRepository.hentSøknad(søknad)
    }
}