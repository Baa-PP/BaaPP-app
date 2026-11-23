package com.agrotech.oviapp.domain.model

import kotlinx.serialization.Serializable

enum class Sex { FEMALE, MALE }
enum class LifeStage { LAMB_LACTATING, LAMB_FATTENING, EWE_REPLACEMENT, ADULT }
enum class ReproductiveStatus { EMPTY, PREGNANT, LACTATING, STUD_RAM }
enum class AnimalStatus { ACTIVE, SOLD, DECEASED }
enum class ParturitionType { SIMPLE, TWIN, TRIPLET, QUADRUPLET, QUINTUPLET }
enum class ConceptionMethod { MN, IA, TE }
enum class EntryReason { BIRTH, PURCHASE, REENTRY, TRANSFER, OTHER }
enum class Role { OWNER, WORKER_BRANCH, VET_TECHNICIAN, ASSOCIATION_SUPERUSER }

/**
 * Orthogonal domain catalogs for sheep registration and field capture (RF-AND-02).
 * Excluded legacy fields (prefix position, letter position, classification, family, wool/milk)
 * are intentionally omitted.
 */
object AnimalCatalogs {
    val BREEDS: List<String> = listOf(
        "Dorper",
        "Pelifolk",
        "Pelibuey",
        "Katahdin",
        "Blackbelly",
        "Suffolk",
        "Charollais",
        "Hampshire",
        "Dorset",
        "Rambouillet",
        "East Friesian",
        "Criollo",
        "Cruza / Comercial"
    )

    val ENTRY_REASONS: List<EntryReason> = EntryReason.entries
    val PARTURITION_TYPES: List<ParturitionType> = ParturitionType.entries
    val CONCEPTION_METHODS: List<ConceptionMethod> = ConceptionMethod.entries
}

@Serializable
data class Animal(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val siniigaChip: String? = null,
    val registroUno: String? = null,
    val adn: String? = null,
    val breed: String,
    val color: String,
    val sex: Sex,
    val birthDateIso: String,
    val weaningDateIso: String? = null,
    val parturitionType: ParturitionType = ParturitionType.SIMPLE,
    val conceptionMethod: ConceptionMethod = ConceptionMethod.MN,
    val sireId: String? = null,
    val damId: String? = null,
    val externalSireFolio: String? = null,
    val externalDamFolio: String? = null,
    val entryReason: String = "BIRTH",
    val criadorName: String? = null,
    val photoS3Key: String? = null,
    val birthEventId: String? = null,
    val mortalityDateIso: String? = null,
    val mortalityCause: String? = null,
    val updatedAtIso: String? = null,
    val lifeStage: LifeStage,
    val reproductiveStatus: ReproductiveStatus,
    val status: AnimalStatus = AnimalStatus.ACTIVE
)
