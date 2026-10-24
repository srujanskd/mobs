import java.io.File;
import java.nio.file.*;
import jadx.api.*;
class ExportSmali {
 public static void main(String[] args) throws Exception {
  JadxArgs config = new JadxArgs();
  config.setInputFile(new File(args[0]));
  config.setSkipResources(true);
  try (JadxDecompiler jadx = new JadxDecompiler(config)) {
   jadx.load();
   Path out=Path.of(args[1]); Files.createDirectories(out);
   int count=0;
   for (JavaClass cls:jadx.getClasses()) {
    String name=cls.getFullName();
    if (name.startsWith("com.wmspanel.") || name.startsWith("LpT5.") || name.startsWith("LPT5.")) {
     Files.writeString(out.resolve(name+".smali"),cls.getSmali()); count++;
    }
   }
   System.out.println("Exported "+count+" classes");
  }
 }
}
