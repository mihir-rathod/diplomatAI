import "./globals.css";

export const metadata = {
  title: "diplomatAI Dashboard",
  description: "Multi-LLM Gateway Dashboard",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
