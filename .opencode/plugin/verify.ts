import type { Plugin } from "@opencode-ai/plugin"

const CHECKS = [
  { name: "cargo test", cmd: "cd rust && cargo test --release --locked" },
  { name: "assembleDebug", cmd: "./gradlew assembleDebug --console=plain" },
  { name: "APK generado", cmd: "ls app/build/outputs/apk/debug/*.apk" },
]

export const Verify: Plugin = async ({ client, $ }) => {
  let sucio = false
  let intentos = 0
  return {
    event: async ({ event }) => {
      if (event.type === "file.edited") sucio = true
      if (event.type !== "session.idle" || !sucio) return

      for (const c of CHECKS) {
        const r = await $`bash -c ${c.cmd}`.nothrow().quiet()
        if (r.exitCode === 0) continue
        if (++intentos > 3) return // corte anti-loop
        const log = (r.stdout.toString() + r.stderr.toString()).split("\n").slice(-40).join("\n")
        await client.session.prompt({
          path: { id: event.properties.sessionID },
          body: { parts: [{ type: "text", text:
            `Falló "${c.name}" (exit ${r.exitCode}):\n${log}\nArreglalo. No termines hasta que pasen cargo test, assembleDebug y exista el APK.` }] },
        })
        return
      }
      sucio = false
      intentos = 0
    },
  }
}