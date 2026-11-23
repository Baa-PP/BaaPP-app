package com.agrotech.oviapp.weaning

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

data class UnweanedLamb(
    val id: String,
    val folioInterno: String,
    val sex: Sex,
    val birthDate: LocalDate,
)

data class WeaningCandidate(
    val animalId: String,
    val folioInterno: String,
    val sex: Sex,
    val birthDate: LocalDate,
    val scheduledDate: LocalDate,
    val ageDays: Int,
    val daysUntilWeaning: Int,
)

data class WeaningList(
    val pending: List<WeaningCandidate>,
    val upcoming: List<WeaningCandidate>,
)

interface WeaningStore {
    /** Animales activos del rancho sin destetar, nacidos hasta [bornOnOrBefore]. */
    fun unweanedLambs(ranchId: String, bornOnOrBefore: LocalDate): List<UnweanedLamb>
}

/** Vista "Destetes pendientes / próximos" calculada solo con `local_animals`, sin conexión. */
class PendingWeaningsUseCase(
    private val store: WeaningStore,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    fun list(
        ranchId: String,
        targetDays: Int = WeaningSchedule.DEFAULT_TARGET_DAYS,
        upcomingWindowDays: Int = WeaningSchedule.DEFAULT_UPCOMING_WINDOW_DAYS,
    ): WeaningList {
        val now = today()
        val bornOnOrBefore = WeaningSchedule.latestBirthDateToList(now, targetDays, upcomingWindowDays)
        val candidates = store.unweanedLambs(ranchId, bornOnOrBefore).map { lamb ->
            val info = WeaningSchedule.forLamb(lamb.birthDate, null, now, targetDays, upcomingWindowDays)
            info.status to WeaningCandidate(
                animalId = lamb.id,
                folioInterno = lamb.folioInterno,
                sex = lamb.sex,
                birthDate = lamb.birthDate,
                scheduledDate = info.scheduledDate,
                ageDays = info.ageDays,
                daysUntilWeaning = info.daysUntilWeaning,
            )
        }
        val order = compareBy<WeaningCandidate>({ it.scheduledDate }, { it.folioInterno })
        return WeaningList(
            pending = candidates.filter { it.first == WeaningStatus.PENDING }.map { it.second }.sortedWith(order),
            upcoming = candidates.filter { it.first == WeaningStatus.UPCOMING }.map { it.second }.sortedWith(order),
        )
    }
}

class SqlDelightWeaningStore(database: OviAppDatabase) : WeaningStore {
    private val queries = database.oviAppDatabaseQueries

    override fun unweanedLambs(ranchId: String, bornOnOrBefore: LocalDate): List<UnweanedLamb> =
        queries.selectUnweanedAnimals(ranchId, bornOnOrBefore.toString()).executeAsList().map { row ->
            UnweanedLamb(
                id = row.id,
                folioInterno = row.folio_interno,
                sex = Sex.valueOf(row.sex),
                birthDate = LocalDate.parse(row.birth_date.take(10)),
            )
        }
}
