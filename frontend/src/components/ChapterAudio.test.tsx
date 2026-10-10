import {afterEach, describe, expect, it} from "vitest";
import {cleanup, render} from "@testing-library/react";
import ChapterAudio from "./ChapterAudio";

afterEach(cleanup);
describe("chapter audio download", () => {
  const ready = {id:"one", status:"succeeded" as const, audioUrl:"/api/v1/generations/one/audio", audioDownloadUrl:"/api/v1/generations/one/audio/download"};
  it("keeps the player and uses the generated API download URL as a normal link", () => {
    const page = render(<ChapterAudio job={ready}/>);
    expect(page.getByLabelText("Luvun podcast")).toBeTruthy();
    const link = page.getByRole("link", {name:"Download audio"});
    expect(link.getAttribute("href")).toBe(ready.audioDownloadUrl);
    expect(link.hasAttribute("download")).toBe(true);
  });
  it("does not offer download before completed audio is ready", () => {
    const page = render(<ChapterAudio job={{...ready, status:"running"}}/>);
    expect(page.queryByRole("link", {name:"Download audio"})).toBeNull();
    page.rerender(<ChapterAudio job={{...ready, audioUrl:undefined}}/>);
    expect(page.queryByRole("link", {name:"Download audio"})).toBeNull();
  });
});
