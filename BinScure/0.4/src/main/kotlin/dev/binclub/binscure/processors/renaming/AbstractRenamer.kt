package dev.binclub.binscure.processors.renaming

import dev.binclub.binscure.CObfuscator
import dev.binclub.binscure.IClassProcessor
import dev.binclub.binscure.classpath.ClassPath
import dev.binclub.binscure.configuration.ConfigurationManager.rootConfig
import dev.binclub.binscure.forClass
import dev.binclub.binscure.processors.renaming.utils.CustomRemapper
import dev.binclub.binscure.utils.AnnotationFieldRemapper
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.tree.ClassNode

/**
 * @author cookiedragon234 24/Jan/2020
 */
abstract class AbstractRenamer: IClassProcessor {
	override val config = rootConfig.remap
	
	final override fun process(classes: MutableCollection<ClassNode>, passThrough: MutableMap<String, ByteArray>) {
		if (!isEnabled())
			return
		
		val remapper = CustomRemapper()
		remap(remapper, classes, passThrough)
		val replacements = mutableMapOf<ClassNode, ClassNode>()
		forClass(classes) { classNode ->
			val newNode = ClassNode()
			val classMapper = ClassRemapper(newNode, remapper)
			classNode.accept(classMapper)
			replacements[classNode] = newNode
			AnnotationFieldRemapper.remap(newNode, remapper)
		}
		
		for ((old, new) in replacements) {
			classes.remove(old)
			classes.add(new)
			ClassPath.classes.remove(old.name)
			ClassPath.classes[new.name] = new
			ClassPath.classPath.remove(old.name, old)
			ClassPath.classPath[new.name] = new
			new.originalName = old.originalName
		}
		
		CObfuscator.mappings.putAll(remapper.dumpMappings())
		ClassPath.reconstructHierarchy()
	}
	
	protected abstract fun remap(remapper: CustomRemapper, classes: Collection<ClassNode>, passThrough: MutableMap<String, ByteArray>)
	protected abstract fun isEnabled(): Boolean
}
