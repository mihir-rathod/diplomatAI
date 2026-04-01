import "./globals.css";

export const metadata = {
  title: "diplomatAI Dashboard",
  description: "AI Traffic Controller — Gateway Dashboard",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
