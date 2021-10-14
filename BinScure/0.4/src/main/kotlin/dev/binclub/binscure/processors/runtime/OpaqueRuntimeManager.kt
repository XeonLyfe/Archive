package dev.binclub.binscure.processors.runtime

import dev.binclub.binscure.CObfuscator
import dev.binclub.binscure.CObfuscator.random
import dev.binclub.binscure.IClassProcessor
import dev.binclub.binscure.classpath.ClassPath
import dev.binclub.binscure.processors.flow.MethodParameterObfuscator
import dev.binclub.binscure.utils.add
import dev.binclub.binscure.utils.random
import dev.binclub.binscure.processors.renaming.generation.NameGenerator
import dev.binclub.binscure.processors.renaming.impl.ClassRenamer
import dev.binclub.binscure.utils.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import kotlin.math.max
import kotlin.math.min

/**
 * This class creates constants that are used for opaque jumps
 *
 * Essentially these are a bunch of fields with predetermined values that we know but are hard for deobfuscators to
 * statically infer
 *
 * We can create jumps on these values like if(a < 0) knowing that a is larger than 0 and therefore the if statement
 * will never succeed
 *
 * @author cookiedragon234 11/Feb/2020
 */
object OpaqueRuntimeManager {
	private val namer = NameGenerator()
	
	val classNodeDelegate = lazy { ClassNode().apply {
		this.access = ACC_PUBLIC
		this.version = V1_8
		this.name = ClassRenamer.namer.uniqueUntakenClass()
		this.signature = null
		this.superName = "java/util/concurrent/ConcurrentHashMap"
		
		MethodNode(0, "<init>", "()V", null, null).apply {
			instructions.apply {
				add(VarInsnNode(ALOAD, 0))
				add(DUP)
				add(MethodInsnNode(INVOKESPECIAL, superName, "<init>", "()V"))
				add(RETURN)
			}
			
			methods.add(this)
		}
	} }
	val classNode by classNodeDelegate
	// Returns the classnode only if its been generated
	fun getClassNodeSafe(): ClassNode? =
		if (classNodeDelegate.isInitialized()) classNode else null
	
	private val clinit by lazy {
		MethodNode(ACC_STATIC, "<clinit>", "()V", null, null).also {
			it.instructions.add(InsnNode(RETURN))
			classNode.methods.add(it)
		}
	}
	
	// The larger the application, the larger the number of fields we want available
	// We will use the number of classes / 2, at least 3 and at most 25
	val fields by lazy {
		Array(min(max(ClassPath.classes.size / 2, 3), 25)) { generateField() }
	}
	
	private fun generateField(): FieldInfo {
		val fieldNode = FieldNode(
			ACC_PUBLIC + ACC_STATIC,
			namer.uniqueRandomString(),
			"I",
			null,
			random.nextInt(Integer.MAX_VALUE)
		).also {
			classNode.fields.add(it)
		}
		return randomBranch(random,
			{
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(0))
				FieldInfo(fieldNode, IFEQ, IFGT)
			}, {
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(1))
				FieldInfo(fieldNode, IFGE, IFLT)
			}, {
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(1))
				FieldInfo(fieldNode, IFGT, IFEQ)
			}, {
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(-1))
				FieldInfo(fieldNode, IFLT, IFGE)
			}, {
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(-1))
				FieldInfo(fieldNode, IFLE, IFGT)
			}, {
				clinit.instructions.insert(FieldInsnNode(PUTSTATIC, classNode.name, fieldNode.name, fieldNode.desc))
				clinit.instructions.insert(ldcInt(-1))
				FieldInfo(fieldNode, IFNE, IFEQ)
			}
		)
	}
	
	data class FieldInfo(
		val fieldNode: FieldNode, // Reference to the field
		val trueOpcode: Int, // An if statement opcode that will return true when compared to this fields value
		val falseOpcode: Int // An if statement opcode that will return false when compared to this fields value
	)
}

fun randomOpaqueJump(target: LabelNode, jumpOver: Boolean = true, mnStr: String? = null): InsnList {
	val field = OpaqueRuntimeManager.fields.random(random)
	return insnBuilder {
		if (mnStr != null) {
			val secret = MethodParameterObfuscator.methodSecrets[mnStr]
			if (secret != null) {
				iload(secret.second)
				randomBranch(random, {
					ldc(secret.first xor 0)
					ixor()
					if (jumpOver)
						ifeq(target)
					else
						ifne(target)
				}, {
					var key = randomInt()
					if (key == 0) key = 1
					ldc(secret.first xor key)
					ixor()
					if (jumpOver)
						ifne(target)
					else
						ifeq(target)
				})
				return@insnBuilder
			}
		}
		getstatic(OpaqueRuntimeManager.classNode.name, field.fieldNode.name, field.fieldNode.desc)
		+JumpInsnNode(
			if (jumpOver) field.trueOpcode else field.falseOpcode,
			target
		)
	}
}

fun opaqueSwitchJump(mnStr: String? = null, jumpSupplier: (LabelNode) -> InsnList = {
	randomOpaqueJump(it, true, mnStr)
}): Pair<InsnList, InsnList> {
	val trueNum = randomInt()
	val falseNum = randomInt()
	val key = randomInt()
	val switch = newLabel()
	val trueLabel = newLabel()
	val falseLabel = newLabel()
	val deadLabel = newLabel()
	val list = InsnList().apply {
		val rand = randomBranch(
			random, {
				var dummyNum: Int
				do {
					dummyNum = randomInt()
				} while (dummyNum == trueNum || dummyNum == falseNum)
				
				add(jumpSupplier(falseLabel))
				add(ldcInt(trueNum xor key))
				add(JumpInsnNode(GOTO, switch))
				add(falseLabel)
				add(ldcInt(dummyNum xor key))
				add(switch)
				add(ldcInt(key))
				add(IXOR)
				add(constructLookupSwitch(
					trueLabel, if(random.nextBoolean()) arrayOf(
						trueNum to deadLabel, falseNum to falseLabel
					) else arrayOf(
						falseNum to falseLabel, trueNum to deadLabel
					)
				))
				add(trueLabel)
			}, {
				add(jumpSupplier(falseLabel))
				add(ldcInt(falseNum xor key))
				add(JumpInsnNode(GOTO, switch))
				add(falseLabel)
				add(ldcInt(trueNum xor key))
				add(switch)
				add(ldcInt(key))
				add(IXOR)
				add(constructLookupSwitch(
					deadLabel, if(random.nextBoolean()) arrayOf(
						trueNum to trueLabel, falseNum to falseLabel
					) else arrayOf(
						falseNum to falseLabel, trueNum to trueLabel
					)
				))
				add(trueLabel)
			}
		)
	}
	
	val otherList = InsnList().apply {
		add(deadLabel)
		add(ACONST_NULL)
		add(ATHROW)
	}
	
	return Pair(list, otherList)
}
