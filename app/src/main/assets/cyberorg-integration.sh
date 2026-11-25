# Cyberorg shell integration (managed by the app; overwritten on every session start).
# Reports exit code, working directory, command and timing to the app via a state file.
[ -n "$BASH_VERSION" ] || return 0 2>/dev/null
[ -n "$CYBERORG_STATE_FILE" ] || return 0 2>/dev/null
[ -z "$__CY_INIT" ] || return 0 2>/dev/null
__CY_INIT=1

__cy_precmd() {
  local ec=$?
  local f="$CYBERORG_STATE_FILE"
  local start=""
  if [ -f "$f.start" ]; then
    start=$(cat "$f.start" 2>/dev/null)
    rm -f "$f.start"
  fi
  local cmd
  cmd=$(HISTTIMEFORMAT= history 1 2>/dev/null | sed 's/^ *[0-9]* *//' | tr '\n' ' ')
  printf 'exit=%d\ncwd=%s\nstart=%s\nend=%s\ncmd=%s\n' "$ec" "$PWD" "$start" "$EPOCHSECONDS" "$cmd" > "$f.tmp" && mv -f "$f.tmp" "$f"
  return $ec
}

PS0='$(printf "%s" "$EPOCHSECONDS" > "$CYBERORG_STATE_FILE.start")'
PROMPT_COMMAND="__cy_precmd${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
