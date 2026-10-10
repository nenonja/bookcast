import type {Generation} from "@/lib/api";
import Player from "./Player";

export default function ChapterAudio({job}: {job: Pick<Generation, "id" | "status" | "audioUrl" | "audioDownloadUrl">}) {
  if (job.status !== "succeeded" || !job.audioUrl) return null;
  return <section aria-label="Chapter audio">
    <Player generationId={job.id} url={job.audioUrl}/>
    {job.audioDownloadUrl && <a href={job.audioDownloadUrl} download>Download audio</a>}
  </section>;
}
