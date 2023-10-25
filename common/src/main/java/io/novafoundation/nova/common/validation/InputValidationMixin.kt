package io.novafoundation.nova.common.validation

import kotlinx.coroutines.flow.Flow

interface InputValidationMixin {
    val fieldValidationResult: Flow<FieldValidationResult>

    interface Factory {
        fun create(inputStream: Flow<String>, validator: FieldValidator)
    }
}

class RealInputValidationMixinFactory(val validator: FieldValidator) {

    fun create(inputStream: Flow<String>): InputValidationMixin {
        return RealInputValidationMixin(inputStream, validator)
    }
}

class RealInputValidationMixin(
    inputStream: Flow<String>,
    fieldValidator: FieldValidator
) : InputValidationMixin {

    override val fieldValidationResult: Flow<FieldValidationResult> = fieldValidator.observe(inputStream)
}
