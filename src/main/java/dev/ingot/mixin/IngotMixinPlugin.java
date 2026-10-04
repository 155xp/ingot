package dev.ingot.mixin;

import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies Ingot's mixins only on macOS with Ingot enabled, so -Dingot.enabled=false leaves the game exactly vanilla.
 * Optional hooks (require = 0) that find nothing to hook after a game update are logged here, since Mixin skips them
 * silently.
 */
public final class IngotMixinPlugin implements IMixinConfigPlugin {
	private static final String MIXIN_INJECTORS = "Lorg/spongepowered/asm/mixin/injection/";
	private static final String MIXINEXTRAS_INJECTORS = "Lcom/llamalad7/mixinextras/injector/";

	private boolean active;

	@Override
	public void onLoad(String mixinPackage) {
		this.active = Ingot.isMacOs() && IngotConfig.get().enabled;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return this.active;
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		for (MethodNode handler : mixinInfo.getClassNode(0).methods) {
			if (isInjector(handler) && !isCalled(targetClass, handler.name)) {
				Ingot.LOGGER.warn("Ingot: {} found nothing to hook in {}; that feature stays vanilla", handler.name, targetClassName);
			}
		}
	}

	private static boolean isInjector(MethodNode method) {
		return hasInjectorAnnotation(method.invisibleAnnotations) || hasInjectorAnnotation(method.visibleAnnotations);
	}

	private static boolean hasInjectorAnnotation(List<AnnotationNode> annotations) {
		if (annotations != null) {
			for (AnnotationNode annotation : annotations) {
				if (annotation.desc.startsWith(MIXIN_INJECTORS) || annotation.desc.startsWith(MIXINEXTRAS_INJECTORS)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Merged handlers are renamed to "prefix$uid$...$name"; an applied injector leaves a call to one in the target. */
	private static boolean isCalled(ClassNode targetClass, String handlerName) {
		String suffix = "$" + handlerName;
		for (MethodNode method : targetClass.methods) {
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(targetClass.name)
					&& (call.name.equals(handlerName) || call.name.endsWith(suffix))) {
					return true;
				}
			}
		}
		return false;
	}
}
