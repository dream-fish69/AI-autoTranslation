import com.dsh.autotranslation.TranslationMemory;

import java.nio.file.Path;

/** Quick check that a memory file can be loaded and queried. */
public final class MemoryProbe {

    public static void main(String[] args) {
        TranslationMemory memory = TranslationMemory.load(Path.of(args[0]));
        System.out.println("entries=" + memory.size());
        for (int i = 1; i < args.length; i++) {
            System.out.println("  " + args[i] + " -> " + memory.lookup(args[i]));
        }
    }
}
