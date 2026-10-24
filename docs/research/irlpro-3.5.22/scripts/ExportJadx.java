import java.io.File;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
class ExportJadx {
 public static void main(String[] args) throws Exception {
  JadxArgs config = new JadxArgs();
  config.setInputFile(new File(args[0]));
  config.setOutDir(new File(args[1]));
  config.setThreadsCount(4);
  config.setShowInconsistentCode(true);
  try (JadxDecompiler jadx = new JadxDecompiler(config)) {
   jadx.load();
   System.out.println("Classes="+jadx.getClasses().size());
   jadx.save();
   jadx.printErrorsReport();
   System.out.println("Errors="+jadx.getErrorsCount()+" warnings="+jadx.getWarnsCount());
  }
 }
}
