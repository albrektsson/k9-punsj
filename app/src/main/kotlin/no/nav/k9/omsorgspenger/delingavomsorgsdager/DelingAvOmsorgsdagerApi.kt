package no.nav.k9.omsorgspenger.delingavomsorgsdager

import kotlinx.coroutines.reactive.awaitFirst
import no.nav.k9.RequestContext
import no.nav.k9.Routes
import no.nav.k9.SøknadType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import kotlin.coroutines.coroutineContext

@Configuration
class DelingAvOmsorgsdagerApi(
        private val delingAvOmsorgsdagerMeldingService: DelingAvOmsorgsdagerMeldingService,
) {
    companion object {
        const val type: SøknadType = "omsorgspenger-deling-av-omsorgsdager-melding"
        private val logger: Logger = LoggerFactory.getLogger(DelingAvOmsorgsdagerApi::class.java)
    }

    @Bean
    fun delingDagerRoutes() = Routes {

        POST("/api/$type") { request ->
            RequestContext(coroutineContext, request) {
                val dto = request.body(BodyExtractors.toMono(DelingAvOmsorgsdagerDTO::class.java)).awaitFirst()

                // Skal se om det er deling eller overføring eller fordeling
                val melding = DelingAvOmsorgsdagerConverter.map(dto)

                delingAvOmsorgsdagerMeldingService.send(melding, dto.dedupKey.toString())
                logger.info("Sendte inn søknad om overføring av dager med dedup key:", dto.dedupKey)

                ServerResponse
                    .status(HttpStatus.ACCEPTED)
                    .buildAndAwait()
            }
        }
    }
}