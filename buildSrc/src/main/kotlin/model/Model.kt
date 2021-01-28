package org.hildan.chrome.devtools.build.model

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.json.JsonElement
import org.hildan.chrome.devtools.build.generator.DocUrls
import org.hildan.chrome.devtools.build.generator.ROOT_PACKAGE_NAME
import org.hildan.chrome.devtools.build.json.*
import kotlin.reflect.KClass

typealias DomainName = String

fun DomainName.asVariableName() = when {
    this[1].isLowerCase() -> decapitalize()
    all { it.isUpperCase() } -> toLowerCase()
    else -> {
        // This handles domains starting with acronyms (DOM, CSS...) by lowercasing the whole acronym
        val firstLowercaseIndex = indexOfFirst { it.isLowerCase() }
        substring(0, firstLowercaseIndex - 1).toLowerCase() + substring(firstLowercaseIndex - 1)
    }
}

val DomainName.packageName
    get() = "$ROOT_PACKAGE_NAME.domains.${toLowerCase()}"

fun DomainName.asClassName() = ClassName(packageName, "${this}Domain")

// Distinct events sub-package to avoid conflicts with domain types
val DomainName.eventsPackageName
    get() = "$packageName.events"

private val DomainName.eventsParentClassName
    get() = ClassName(eventsPackageName, "${this}Event")

data class ChromeDPDomain(
    val name: DomainName,
    val description: String? = null,
    val deprecated: Boolean = false,
    val experimental: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val types: List<DomainTypeDeclaration> = emptyList(),
    val commands: List<ChromeDPCommand> = emptyList(),
    val events: List<ChromeDPEvent> = emptyList()
) {
    val packageName = name.packageName
    val eventsPackageName = name.eventsPackageName
    val eventsParentClassName = name.eventsParentClassName
    val docUrl = DocUrls.domain(name)
}

fun sanitize(domain: JsonDomain): ChromeDPDomain = ChromeDPDomain(name = domain.domain,
    description = domain.description,
    deprecated = domain.deprecated,
    experimental = domain.experimental,
    dependencies = domain.dependencies,
    types = domain.types.map { it.toTypeDeclaration(domain.domain) },
    commands = domain.commands.map { it.toCommand(domain.domain) },
    events = domain.events.map { it.toEvent(domain.domain) }
)

data class DomainTypeDeclaration(
    val name: String,
    val domainName: DomainName,
    val description: String? = null,
    val deprecated: Boolean = false,
    val experimental: Boolean = false,
    val type: ChromeDPType
) {
    val docUrl = DocUrls.type(domainName, name)
}

private fun JsonDomainType.toTypeDeclaration(domainName: DomainName): DomainTypeDeclaration =
    DomainTypeDeclaration(
        name = id,
        domainName = domainName,
        description = description,
        deprecated = deprecated,
        experimental = experimental,
        type = ChromeDPType.of(
            type = type,
            properties = properties,
            enum = enum,
            items = items,
            domainName = domainName
        )
    )

data class ChromeDPParameter(
    val name: String,
    val description: String? = null,
    val optional: Boolean = false,
    val deprecated: Boolean = false,
    val experimental: Boolean = false,
    val type: ChromeDPType
)

private fun JsonDomainParameter.toParameter(domainName: DomainName): ChromeDPParameter = ChromeDPParameter(
    name = name,
    description = description,
    optional = optional,
    deprecated = deprecated,
    experimental = experimental,
    type = ChromeDPType.of(type = type, enum = enum, items = items, ref = `$ref`, domainName = domainName)
)

data class ChromeDPCommand(
    val name: String,
    val domainName: DomainName,
    val description: String? = null,
    val deprecated: Boolean = false,
    val experimental: Boolean = false,
    val redirect: String? = null,
    val parameters: List<ChromeDPParameter> = emptyList(),
    val returns: List<ChromeDPParameter> = emptyList()
) {
    val docUrl = DocUrls.command(domainName, name)
    val inputTypeName = when {
        parameters.isEmpty() -> null
        else -> ClassName(domainName.packageName, "${name.capitalize()}Request")
    }
    val outputTypeName = when {
        returns.isEmpty() -> Unit::class.asTypeName()
        else -> ClassName(domainName.packageName, "${name.capitalize()}Response")
    }
}

private fun JsonDomainCommand.toCommand(domainName: DomainName) = ChromeDPCommand(
    name = name,
    domainName = domainName,
    description = description,
    deprecated = deprecated,
    experimental = experimental,
    redirect = redirect,
    parameters = parameters.map { it.toParameter(domainName) },
    returns = returns.map { it.toParameter(domainName) }
)

data class ChromeDPEvent(
    val name: String,
    val domainName: DomainName,
    val description: String? = null,
    val deprecated: Boolean = false,
    val experimental: Boolean = false,
    val parameters: List<ChromeDPParameter> = emptyList()
) {
    val docUrl = DocUrls.event(domainName, name)
    val eventTypeName
        get() = domainName.eventsParentClassName.nestedClass("${name.capitalize()}Event")
}

private fun JsonDomainEvent.toEvent(domainName: DomainName) = ChromeDPEvent(
    name = name,
    domainName = domainName,
    description = description,
    deprecated = deprecated,
    experimental = experimental,
    parameters = parameters.map { it.toParameter(domainName) }
)

sealed class ChromeDPType {

    abstract fun toTypeName(): TypeName

    object Unknown : ChromeDPType() {
        override fun toTypeName(): TypeName = JsonElement::class.asClassName()
    }

    data class Primitive<T : Any>(val type: KClass<T>) : ChromeDPType() {
        override fun toTypeName(): TypeName = type.asTypeName()
    }

    data class Reference(val typeName: String, val domainName: String) : ChromeDPType() {
        override fun toTypeName(): TypeName = ClassName(domainName.packageName, typeName)
    }

    data class Enum(val enumValues: List<String>, val domainName: String) : ChromeDPType() {
        // enums don't have to be declared types, but without names they have to be just strings
        override fun toTypeName(): TypeName = String::class.asTypeName()
    }

    data class Array(val itemType: ChromeDPType) : ChromeDPType() {
        override fun toTypeName(): TypeName = LIST.parameterizedBy(itemType.toTypeName())
    }

    data class Object(val properties: List<ChromeDPParameter>, val domainName: String) : ChromeDPType() {
        override fun toTypeName(): TypeName = error("No proper name for object type")
    }

    companion object {
        fun of(
            type: String?,
            properties: List<JsonDomainParameter> = emptyList(),
            enum: List<String>? = null,
            items: ArrayItemDescriptor? = null,
            ref: String? = null,
            domainName: String
        ) = when (type) {
            "string" -> if (enum != null) Enum(enum, domainName) else Primitive(String::class)
            "boolean" -> Primitive(Boolean::class)
            "integer" -> Primitive(Int::class)
            "number" -> Primitive(Double::class)
            "any" -> Unknown
            "array" -> Array(items?.toChromeDPType(domainName) ?: error("Missing 'items' property on array type"))
            "object" -> if (properties.isEmpty()) {
                Unknown
            } else {
                Object(properties.map { it.toParameter(domainName) }, domainName)
            }
            null -> reference(ref ?: error("Either 'type' or '\$ref' should be present"), domainName)
            else -> error("Unknown kind of type '$type'")
        }

        private fun reference(ref: String, domainName: String): Reference {
            val domain = if (ref.contains(".")) ref.substringBefore('.') else domainName
            val typeName = if (ref.contains(".")) ref.substringAfter(".") else ref
            return Reference(typeName, domain)
        }
    }
}

private fun ArrayItemDescriptor.toChromeDPType(domainName: String): ChromeDPType =
    ChromeDPType.of(type = type, enum = enum, ref = `$ref`, domainName = domainName)
