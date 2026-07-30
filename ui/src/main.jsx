import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import ContactSheet from "./App.jsx";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <ContactSheet />
  </StrictMode>
);
