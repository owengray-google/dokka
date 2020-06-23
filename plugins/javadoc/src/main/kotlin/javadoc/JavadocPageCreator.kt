package javadoc

import javadoc.pages.*
import org.jetbrains.dokka.DokkaConfiguration.DokkaSourceSet
import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.base.signatures.SignatureProvider
import org.jetbrains.dokka.base.signatures.function
import org.jetbrains.dokka.base.transformers.pages.comments.CommentsToContentConverter
import org.jetbrains.dokka.base.transformers.pages.comments.DocTagToContentConverter
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.doc.Description
import org.jetbrains.dokka.model.doc.TagWrapper
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.model.properties.WithExtraProperties
import org.jetbrains.dokka.pages.ContentKind
import org.jetbrains.dokka.pages.ContentNode
import org.jetbrains.dokka.pages.ContentText
import org.jetbrains.dokka.pages.DCI
import org.jetbrains.dokka.utilities.DokkaLogger
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

open class JavadocPageCreator(
    commentsToContentConverter: CommentsToContentConverter,
    private val signatureProvider: SignatureProvider,
    val logger: DokkaLogger
) {

    fun pageForModule(m: DModule): JavadocModulePageNode =
        JavadocModulePageNode(
            name = m.name.ifEmpty { "root" },
            content = contentForModule(m),
            children = m.packages.map { pageForPackage(it) },
            dri = setOf(m.dri)
        )

    fun pageForPackage(p: DPackage) =
        JavadocPackagePageNode(p.name, contentForPackage(p), setOf(p.dri), p,
            p.classlikes.mapNotNull { pageForClasslike(it) } // TODO: nested classlikes
        )

    fun pageForClasslike(c: DClasslike): JavadocClasslikePageNode? =
        c.highestJvmSourceSet?.let { jvm ->
            JavadocClasslikePageNode(
                name = c.name.orEmpty(),
                content = contentForClasslike(c),
                dri = setOf(c.dri),
                modifiers = listOfNotNull(c.visibility[jvm]?.name),
                signature = signatureProvider.signature(c).nodeForJvm(jvm),
                description = c.descriptionToContentNodes(),
                constructors = c.safeAs<WithConstructors>()?.constructors?.map { it.toJavadocFunction(jvm) }.orEmpty(),
                methods = c.functions.map { it.toJavadocFunction(jvm) },
                entries = c.safeAs<DEnum>()?.entries?.map { JavadocEntryNode(signatureProvider.signature(it).nodeForJvm(jvm), it.descriptionToContentNodes(jvm)) }.orEmpty(),
                classlikes = c.classlikes.mapNotNull { pageForClasslike(it) },
                properties = c.properties.map { JavadocPropertyNode(signatureProvider.signature(it).nodeForJvm(jvm), it.descriptionToContentNodes(jvm)) },
                documentable = c,
                extras = c.safeAs<WithExtraProperties<Documentable>>()?.extra ?: PropertyContainer.empty()
            )
        }

    private fun contentForModule(m: DModule): JavadocContentNode =
        JavadocContentGroup(
            setOf(m.dri),
            JavadocContentKind.OverviewSummary,
            m.jvmSource.toSet()
        ) {
            title(m.name, m.brief(),"0.0.1", dri = setOf(m.dri), kind = ContentKind.Main)
            list("Packages", "Package", setOf(m.dri), ContentKind.Packages, m.packages.sortedBy { it.name }.map { p ->
                RowJavadocListEntry(
                    LinkJavadocListEntry(p.name, setOf(p.dri), JavadocContentKind.PackageSummary, sourceSets),
                    p.brief()
                )
            })
        }

    private fun contentForPackage(p: DPackage): JavadocContentNode =
        JavadocContentGroup(
            setOf(p.dri),
            JavadocContentKind.PackageSummary,
            p.jvmSource.toSet()
        ) {
            title(p.name, p.brief(),"0.0.1", dri = setOf(p.dri), kind = ContentKind.Packages)
            list("Packages", "Package", setOf(p.dri), ContentKind.Packages, p.classlikes.sortedBy { it.name }.map { c ->
                RowJavadocListEntry(
                    LinkJavadocListEntry(c.name.orEmpty(), setOf(c.dri), JavadocContentKind.Class, sourceSets),
                    c.brief()
                )
            })
        }

    private fun contentForClasslike(c: DClasslike): JavadocContentNode =
        JavadocContentGroup(
            setOf(c.dri),
            JavadocContentKind.Class,
            c.jvmSource.toSet()
        ) {
            title(
                c.name.orEmpty(),
                c.brief(),
                "0.0.1",
                parent = c.dri.packageName,
                dri = setOf(c.dri),
                kind = JavadocContentKind.Class
            )
        }

    private fun signatureForProjection(p: Projection): String =
        when (p) {
            is OtherParameter -> p.name
            is TypeConstructor -> if (p.function)
                "TODO"
            else {
                val other = if (p.projections.isNotEmpty()) {
                    p.projections.joinToString(prefix = "<", postfix = ">") { signatureForProjection(it) }
                } else {
                    ""
                }
                "${p.dri.classNames.orEmpty()} $other"
            }

            is Variance -> "${p.kind} ${signatureForProjection(p.inner)}"
            is Star -> "*"
            is Nullable -> "${signatureForProjection(p.inner)}?"
            is JavaObject -> "Object"
            is Void -> "Void"
            is PrimitiveJavaType -> p.name
            is Dynamic -> "dynamic"
            is UnresolvedBound -> p.name
        }

    private fun DFunction.toJavadocFunction(sourceSetData: DokkaSourceSet) = JavadocFunctionNode(
        name = name,
        signature = signatureProvider.signature(this).nodeForJvm(sourceSetData),
        brief = brief(sourceSetData),
        parameters = parameters.map {
            JavadocParameterNode(
                name = it.name.orEmpty(),
                type = signatureForProjection(it.type),
                description = it.brief()
            )
        },
        extras = extra
    )

    private val Documentable.jvmSource
        get() = sourceSets.filter { it.analysisPlatform == Platform.jvm }

    private val Documentable.highestJvmSourceSet
        get() = jvmSource.let { sources ->
            sources.firstOrNull { it !=  expectPresentInSet } ?: sources.firstOrNull()
        }

    private val firstSentenceRegex = Regex("^((?:[^.?!]|[.!?](?!\\s))*[.!?])")

    private inline fun <reified T: TagWrapper> Documentable.findNodeInDocumentation(sourceSetData: DokkaSourceSet?): T? =
        documentation[sourceSetData]?.firstChildOfType<T>()

    private fun Documentable.descriptionToContentNodes(sourceSet: DokkaSourceSet? = highestJvmSourceSet) = findNodeInDocumentation<Description>(sourceSet)?.let {
        DocTagToContentConverter.buildContent(
            it.root,
            DCI(setOf(dri), JavadocContentKind.OverviewSummary),
            sourceSets.toSet()
        )
    }.orEmpty()

    fun List<ContentNode>.nodeForJvm(jvm: DokkaSourceSet): ContentNode =
        first { it.sourceSets.contains(jvm) }

    private fun Documentable.brief(sourceSet: DokkaSourceSet? = highestJvmSourceSet): List<ContentNode> {
        val description = descriptionToContentNodes(sourceSet)
        val contents = mutableListOf<ContentNode>()
        for (node in description) {
            if ( node is ContentText && firstSentenceRegex.containsMatchIn(node.text) ) {
                contents.add(node.copy(text = firstSentenceRegex.find(node.text)?.value.orEmpty()))
                break
            } else {
                contents.add(node)
            }
        }
        return contents
    }
}
