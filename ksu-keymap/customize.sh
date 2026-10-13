#!/system/bin/sh
# Pad3 Keymap (Layer A) - static keylayout overlay for the OnePlus Pad 3 keyboard.
ui_print "- Pad3 Keymap (Layer A)"
ui_print "  Esc      scan 158   BACK     -> ESCAPE"
ui_print "  Search   scan 193   F23      -> FUNCTION"
ui_print "  Top row  scans 224..115      -> F1..F12"
ui_print "  Del (111) and everything else: untouched"
ui_print "  Reboot required to load the keylayout."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
