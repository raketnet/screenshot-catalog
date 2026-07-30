import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Builds straight into the Java project's resources/public, so `mvn package`
// bundles the compiled UI into the runnable jar - no separate deploy step.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/public",
    emptyOutDir: true,
  },
});
