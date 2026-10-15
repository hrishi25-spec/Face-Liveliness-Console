import { createFileRoute } from "@tanstack/react-router";
import { GuideReader } from "@/components/guide-reader";
import guideSource from "@/content/repo-organization.md?raw";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <GuideReader source={guideSource} />;
}
