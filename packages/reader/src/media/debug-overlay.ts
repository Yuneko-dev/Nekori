export class PlayerDebugOverlay {
  private readonly document: Document;
  private readonly onToggle: () => void;
  private overlay: HTMLDivElement | null = null;
  private toggle: HTMLButtonElement | null = null;

  constructor(document: Document, onToggle: () => void) {
    this.document = document;
    this.onToggle = onToggle;
  }

  attach(enabled: boolean): void {
    if (this.overlay) return;
    this.overlay = this.document.createElement("div");
    this.overlay.id = "nekori-debug-overlay";
    this.document.body.append(this.overlay);
    this.set(enabled);
  }

  set(enabled: boolean): void {
    if (!this.overlay) return;
    if (enabled && !this.toggle) {
      this.toggle = this.document.createElement("button");
      this.toggle.id = "nekori-debug-toggle";
      this.toggle.type = "button";
      this.toggle.addEventListener("click", this.onToggle);
      this.document.body.append(this.toggle);
    }
    this.overlay?.classList.toggle("active", enabled);
    this.toggle?.setAttribute("aria-expanded", String(enabled));
    this.toggle?.setAttribute("aria-label", enabled ? "Hide player log" : "Show player log");
  }

  log(message: string): void {
    if (!this.overlay) return;
    const line = this.document.createElement("div");
    line.className = "nekori-debug-msg";
    line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
    this.overlay.append(line);
    this.overlay.scrollTop = this.overlay.scrollHeight;
  }

  dispose(): void {
    this.overlay?.remove();
    this.toggle?.remove();
    this.overlay = null;
    this.toggle = null;
  }
}
