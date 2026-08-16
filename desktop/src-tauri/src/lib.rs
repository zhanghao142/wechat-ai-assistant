#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .plugin(tauri_plugin_notification::init())
    .setup(|app| {
      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }

      // Windows：把系统标题栏（最小化/最大化/关闭那一行）刷成与界面一致的浅色
      #[cfg(windows)]
      {
        use tauri::Manager;
        if let Some(window) = app.get_webview_window("main") {
          let _ = window.set_theme(Some(tauri::Theme::Light));
          if let Ok(hwnd) = window.hwnd() {
            win_titlebar::apply_unified_color(hwnd.0 as *mut std::ffi::c_void);
          }
        }
      }

      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}

/// 通过 DWM 设置标题栏/边框为统一浅色（仅 Windows 11+ 完整生效）。
#[cfg(windows)]
mod win_titlebar {
  use std::ffi::c_void;

  type HWND = *mut c_void;
  type HRESULT = i32;

  // https://learn.microsoft.com/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute
  const DWMWA_BORDER_COLOR: u32 = 34;
  const DWMWA_CAPTION_COLOR: u32 = 35;
  const DWMWA_TEXT_COLOR: u32 = 36;

  #[link(name = "dwmapi")]
  extern "system" {
    fn DwmSetWindowAttribute(
      hwnd: HWND,
      dw_attribute: u32,
      pv_attribute: *const c_void,
      cb_attribute: u32,
    ) -> HRESULT;
  }

  pub fn apply_unified_color(hwnd: HWND) {
    // COLORREF = 0x00BBGGRR，对应界面 --bg-sidebar #f7f7f8
    let caption: u32 = 0x00F8F7F7;
    let text: u32 = 0x000D0D0D; // --text #0d0d0d
    unsafe {
      let _ = DwmSetWindowAttribute(
        hwnd,
        DWMWA_CAPTION_COLOR,
        &caption as *const u32 as *const c_void,
        4,
      );
      let _ = DwmSetWindowAttribute(
        hwnd,
        DWMWA_BORDER_COLOR,
        &caption as *const u32 as *const c_void,
        4,
      );
      let _ = DwmSetWindowAttribute(
        hwnd,
        DWMWA_TEXT_COLOR,
        &text as *const u32 as *const c_void,
        4,
      );
    }
  }
}
