using System;
using System.IO;
using System.Text;
using System.Web.Script.Serialization;

// A native Windows argv receiver, not a Claude/Codex/Pi implementation.
public class ArgvProbe {
    public static void Main(string[] args) {
        if (Array.IndexOf(args, "--help") >= 0) {
            Console.WriteLine("  --no-alt-screen\n  --tui-mode <mode>");
            return;
        }
        File.WriteAllText(Environment.GetEnvironmentVariable("IMUX_ARGV_OUTPUT"),
            new JavaScriptSerializer().Serialize(args), new UTF8Encoding(false));
    }
}
