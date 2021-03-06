package jigit.settings;

import jigit.indexer.repository.RepoType;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

public final class JigitRepoTypeAdapterFactory extends PostProcessAdapterFactory {
    protected void postProcess(@NotNull Object obj) {
        if (!(obj instanceof JigitRepo)) {
            return;
        }
        try {
            final Field[] declaredFields = JigitRepo.class.getDeclaredFields();
            for (final Field field : declaredFields) {
                if (field.getType() == RepoType.class) {
                    field.setAccessible(true);
                    if (field.get(obj) == null) {
                        field.set(obj, RepoType.SingleRepository);
                    }
                }
            }
        } catch (final IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
