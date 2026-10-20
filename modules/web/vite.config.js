import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
  // A relative base lets the same build work on GitHub Pages under /<repo>/.
  base: "./",
  plugins: [
    scalaJSPlugin({
      // The sbt build is at the repository root, two levels up.
      cwd: "../..",
      projectID: "web",
    }),
  ],
});
