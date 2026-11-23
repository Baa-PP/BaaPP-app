package com.agrotech.oviapp.animals.domain.model

import com.agrotech.oviapp.domain.model.Sex

data class ParentOption(
    val id: String,
    val ranchId: String,
    val folioInterno: String,
    val breed: String,
    val sex: Sex
)

data class ParentSelectionState(
    val damId: String? = null,
    val sireId: String? = null,
    val externalDamFolio: String = "",
    val externalSireFolio: String = ""
)
