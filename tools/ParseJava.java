import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Syntax-only check for Android sources; this does NOT replace an Android SDK build. */
public final class ParseJava {
    public static void main(String[] args) throws Exception {
        List<File> files;
        try (var paths=Files.walk(Path.of(args[0]))) {
            files=paths.filter(p->p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        try (var manager=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("--release","17","-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            task.parse();
            boolean fail=false;
            for(var d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) { System.err.println(d);fail=true; }
            if(fail) System.exit(1);
        }
        System.out.println("PASS: Java 17 syntax parsed for "+files.size()+" Android source files (not an Android type-check/build).");
    }
}
