$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
namespace HuginnLnk {
  [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellLinkW {
    void GetPath(IntPtr f, int c, IntPtr fd, uint fl);
    void GetIDList(out IntPtr p);
    void SetIDList(IntPtr p);
    void GetDescription(IntPtr s, int c);
    void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetWorkingDirectory(IntPtr s, int c);
    void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetArguments(IntPtr s, int c);
    void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetHotkey(out short h);
    void SetHotkey(short h);
    void GetShowCmd(out int c);
    void SetShowCmd(int c);
    void GetIconLocation(IntPtr s, int c, out int i);
    void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string s, int i);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string s, uint r);
    void Resolve(IntPtr h, uint f);
    void SetPath([MarshalAs(UnmanagedType.LPWStr)] string s);
  }
  [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPropertyStore {
    void GetCount(out uint c);
    void GetAt(uint i, out PropertyKey k);
    void GetValue(ref PropertyKey k, IntPtr v);
    void SetValue(ref PropertyKey k, ref PropVariant v);
    void Commit();
  }
  [StructLayout(LayoutKind.Sequential, Pack = 4)]
  public struct PropertyKey { public Guid fmtid; public uint pid; }
  [StructLayout(LayoutKind.Explicit, Size = 24)]
  public struct PropVariant { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr p; }
  [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
  class CShellLink {}
  public static class Maker {
    public static void Make(string lnk, string exe, string args, string dir, string desc, string aumid) {
      var link = (IShellLinkW)new CShellLink();
      link.SetPath(exe);
      link.SetArguments(args);
      link.SetShowCmd(7); // SW_SHOWMINNOACTIVE: the console the tray role detaches from never takes focus
      link.SetWorkingDirectory(dir);
      link.SetDescription(desc);
      var store = (IPropertyStore)link;
      var key = new PropertyKey { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };
      var pv = new PropVariant { vt = 31, p = Marshal.StringToCoTaskMemUni(aumid) };
      try { store.SetValue(ref key, ref pv); store.Commit(); }
      finally { Marshal.FreeCoTaskMem(pv.p); }
      ((IPersistFile)link).Save(lnk, true);
    }
  }
}
'@
New-Item -ItemType Directory -Force -Path 'C:\Users\O''Brien\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup' | Out-Null
[HuginnLnk.Maker]::Make('C:\Users\O''Brien\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Huginn.lnk', 'C:\Users\O''Brien\AppData\Local\huginn\bin\huginn.exe', 'core tray', 'C:\Users\O''Brien\AppData\Local\huginn\bin', 'Huginn', 'com.silencelen.huginn.desktop-kt')
if (-not (Test-Path -LiteralPath 'C:\Users\O''Brien\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Huginn.lnk')) { throw 'the shortcut was not written' }
