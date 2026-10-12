package io.earthplatform.minecraft.mixin;

import io.earthplatform.compat.neoforge.NamesOnlyTempFiles;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Exact private traversal substitution. Original cleanup/write/error policy stays upstream. */
@Mixin(targets="net.neoforged.neoforge.common.IOUtilities",remap=false)
public abstract class NeoForgeTempFileMixin {
    @Redirect(method="tryListTempFiles(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/util/List;",
        at=@At(value="INVOKE",target="Ljava/nio/file/Files;find(Ljava/nio/file/Path;ILjava/util/function/BiPredicate;[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;",remap=false),
        require=1,allow=1,remap=false)
    private static Stream<Path> earthplatform$namesOnlyTempFiles(Path root,int depth,
            BiPredicate<Path,BasicFileAttributes> predicate,FileVisitOption[] options) throws IOException {
        return NamesOnlyTempFiles.namesOnlyDepthOne(root,depth,predicate,options);
    }
}
