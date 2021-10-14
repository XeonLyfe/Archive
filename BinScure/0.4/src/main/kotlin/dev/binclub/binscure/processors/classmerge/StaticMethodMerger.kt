package dev.binclub.binscure.processors.classmerge

import dev.binclub.binscure.IClassProcessor
import dev.binclub.binscure.api.transformers.FlowObfuscationConfiguration
import dev.binclub.binscure.api.transformers.MergeMethods.NONE
import dev.binclub.binscure.classpath.ClassPath
import dev.binclub.binscure.classpath.ClassTree
import dev.binclub.binscure.configuration.ConfigurationManager.rootConfig
import dev.binclub.binscure.processors.constants.string.StringObfuscator
import dev.binclub.binscure.processors.renaming.generation.NameGenerator
import dev.binclub.binscure.processors.renaming.impl.ClassRenamer
import dev.binclub.binscure.utils.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.getArgumentTypes
import org.objectweb.asm.Type.getReturnType
import org.objectweb.asm.tree.*

/**
 * @author cookiedragon234 21/Feb/2020
 */
object StaticMethodMerger: IClassProcessor {
	private fun containsSpecial(classNode: ClassNode, insnList: InsnList): Boolean {
		val hierarchy = ClassPath.getHierarchy(classNode.name)?.allParents
		for (insn in insnList) {
			if (insn.opcode == INVOKESPECIAL || insn.opcode == INVOKEDYNAMIC) return true
			
			if (insn is FieldInsnNode) {
				if (hierarchy?.contains(insn.owner) == true) {
					return true
				}
			}
			if (insn is MethodInsnNode) {
				if (hierarchy?.contains(insn.owner) == true) {
					return true
				}
			}
		}
		
		return false
	}
	
	private fun containsSpecial(insnList: InsnList, classTree: ClassTree?, classNode: ClassNode): Boolean {
		val supers = classTree?.parents?.map {it.getName()}?.toMutableList()?.also {
			it.add(classNode.name)
		} ?: arrayListOf(classNode.name, classNode.superName)
		for (insn in insnList) {
			if (insn is MethodInsnNode && insn.opcode == INVOKESPECIAL && supers.contains(insn.owner)) return true
		}
		
		return false
	}
	
	override val progressDescription: String
		get() = "Merging methods"
	override val config: FlowObfuscationConfiguration = rootConfig.flowObfuscation
	
	override fun process(classes: MutableCollection<ClassNode>, passThrough: MutableMap<String, ByteArray>) {
		if (!config.enabled || config.mergeMethods == NONE) {
			return
		}
		
		val staticMethods: MutableMap<String, MutableSet<Pair<ClassNode, MethodNode>>> = hashMapOf()
		
		for (classNode in classes) {
			if (isExcluded(classNode))
				continue
			
			val hierarchy = ClassPath.getHierarchy(classNode.name)
			
			for (method in classNode.methods) {
				if (isExcluded(classNode, method))
					continue
				
				if (
					!method.name.startsWith('<')
					&&
					!method.access.hasAccess(ACC_ABSTRACT)
					&&
					!method.access.hasAccess(ACC_NATIVE)
					&&
					!containsSpecial(classNode, method.instructions)
				) {
					staticMethods.getOrPut(method.desc, { hashSetOf() }).add(Pair(classNode, method))
				}
			}
		}
		
		var classNode: ClassNode? = null
		
		if (staticMethods.isNotEmpty()) {
			val namer = NameGenerator(rootConfig.remap.methodPrefix)
			
			for ((desc, methods) in staticMethods) {
				val it = methods.shuffled(random).iterator()
				while (it.hasNext()) {
					val (firstClass, firstMethod) = it.next()
					
					val (secondClass, secondMethod) =
						if (it.hasNext()) it.next() else continue
					
					val firstStatic = firstMethod.access.hasAccess(ACC_STATIC)
					val secondStatic = secondMethod.access.hasAccess(ACC_STATIC)
					
					if (classNode == null || classNode.methods.size >= 65530) {
						classNode = ClassNode().apply {
							access = ACC_PUBLIC
							version = classes.first().version
							name = ClassRenamer.namer.uniqueUntakenClass()
							superName = "java/lang/Object"
							ClassPath.classes[this.name] = this
							ClassPath.classPath[this.name] = this
						}
					}
					
					val newMethod = MethodNode(
						ACC_PUBLIC + ACC_STATIC,
						namer.uniqueRandomString(),
						firstMethod.desc.replace("(", "(Ljava/lang/Object;I"),
						null,
						null
					)
					classNode.methods.add(newMethod)
					
					newMethod.tryCatchBlocks = firstMethod.tryCatchBlocks ?: arrayListOf()
					firstMethod.tryCatchBlocks = null
					
					newMethod.localVariables = incrementLocalVars(firstMethod.localVariables?: arrayListOf(), firstStatic)
					firstMethod.localVariables = null
					
					val baseInt = random.nextInt(Integer.MAX_VALUE - 2)
					val keyInt = random.nextInt(Integer.MAX_VALUE)
					
					val firstStart = newLabel()
					val secondStart = newLabel()
					newMethod.instructions = InsnList().apply {
						val default = newLabel()
						add(default)
						add(VarInsnNode(ILOAD, 1))
						add(ldcInt(keyInt))
						add(IXOR)
						add(
							TableSwitchInsnNode(
								baseInt, baseInt + 1,
								default,
								firstStart, secondStart
							)
						)
						add(secondStart)
						if (secondMethod != null) {
							add(incAllVarInsn(secondMethod.instructions, secondStatic, secondClass.name))
						} else {
							InsnNode(ACONST_NULL)
							InsnNode(ATHROW)
						}
						add(firstStart)
						add(incAllVarInsn(firstMethod.instructions, firstStatic, firstClass.name))
					}
					
					firstMethod.instructions = InsnList().apply {
						if (!firstStatic) {
							add(VarInsnNode(ALOAD, 0))
						} else {
							add(ACONST_NULL)
						}
						add(ldcInt(baseInt xor keyInt))
						
						val params = getArgumentTypes(firstMethod.desc)
						var i = if (firstStatic) 0 else (1)
						for (param in params) {
							add(VarInsnNode(getLoadForType(param), i))
							i += 1
							if (param.sort == Type.DOUBLE || param.sort == Type.LONG) {
								i += 1
							}
						}
						add(MethodInsnNode(INVOKESTATIC, classNode.name, newMethod.name, newMethod.desc))
						add(getRetForType(getReturnType(firstMethod.desc)))
					}
					
					secondMethod.instructions = InsnList().apply {
						if (!secondStatic) {
							add(VarInsnNode(ALOAD, 0))
						} else {
							add(ACONST_NULL)
						}
						add(ldcInt((baseInt + 1) xor keyInt))
						
						val params = getArgumentTypes(secondMethod.desc)
						var i = if (secondStatic) 0 else (1)
						for (param in params) {
							add(VarInsnNode(getLoadForType(param), i))
							i += 1
							if (param.sort == Type.DOUBLE || param.sort == Type.LONG) {
								i += 1
							}
						}
						add(MethodInsnNode(INVOKESTATIC, classNode.name, newMethod.name, newMethod.desc))
						add(getRetForType(getReturnType(secondMethod.desc)))
					}
					
					if (secondMethod.tryCatchBlocks != null) newMethod.tryCatchBlocks.addAll(secondMethod.tryCatchBlocks)
					secondMethod.tryCatchBlocks = null
					if (secondMethod.localVariables != null) newMethod.localVariables.addAll(incrementLocalVars(secondMethod.localVariables, secondStatic))
					secondMethod.localVariables = null
					
					StringObfuscator.decryptNode?.let { decryptNode ->
						val modifier = InstructionModifier()
						for (insn in newMethod.instructions) {
							if (insn is MethodInsnNode) {
								if (
									insn.owner == decryptNode.name
									&&
									insn.name == StringObfuscator.fastDecryptMethod.name
								) {
									insn.name = StringObfuscator.decryptMethod.name
									insn.desc = StringObfuscator.decryptMethod.desc
									modifier.prepend(insn, InsnList().apply {
										add(ldcInt(3))
									})
								}
							}
						}
						modifier.apply(newMethod)
					}
				}
			}
		}
	}
	
	private fun <T: MutableCollection<LocalVariableNode>> incrementLocalVars(vars: T, static: Boolean): T {
		val toRemove = arrayListOf<LocalVariableNode>()
		for (localVar in vars) {
			if (localVar.index != 0 || static) {
				localVar.index += 1
			} else {
				//toRemove.add(localVar)
			}
		}
		vars.removeAll(toRemove)
		return vars
	}
	
	private fun incAllVarInsn(insnList: InsnList, static: Boolean, classType: String): InsnList {
		val incAmmount = if (static) 2 else 1
		return InsnList().apply {
			for (insn in insnList) {
				if (insn is VarInsnNode) {
					if (!static && insn.`var` == 0) {
						add(insn)
						add(TypeInsnNode(CHECKCAST, classType))
						continue
					} else {
						add(VarInsnNode(insn.opcode, insn.`var` + (incAmmount)))
						continue
					}
				} else if (insn is IincInsnNode) {
					add(IincInsnNode(insn.`var` + (incAmmount), insn.incr))
					continue
				}
				add(insn)
			}
		}
	}
	
	//fun shuffleArguments(static: Boolean, args: Array<out Type>): Array<Int> {
	//	args.asList().shuffled()
	//	return Array(args.size) {
	//
	//	}
	//}
}
