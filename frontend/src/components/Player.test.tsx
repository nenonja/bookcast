import {describe,it,expect,afterEach} from "vitest";
import {render,fireEvent,cleanup} from "@testing-library/react";
import Player,{resumeKey} from "./Player";
afterEach(()=>{cleanup();localStorage.clear();});
describe("local listening resume",()=>{
 it("restores position, seeks, changes speed and persists subsequent playback",()=>{
  localStorage.setItem(resumeKey("one"),"42");const page=render(<Player url="/audio" generationId="one"/>);const audio=page.getByLabelText("Luvun podcast") as HTMLAudioElement;
  Object.defineProperty(audio,"duration",{value:180,configurable:true});fireEvent.loadedMetadata(audio);expect(audio.currentTime).toBe(42);
  fireEvent.click(page.getByText("+15 s"));expect(audio.currentTime).toBe(57);expect(localStorage.getItem(resumeKey("one"))).toBe("57");
  fireEvent.change(page.getByLabelText("Nopeus"),{target:{value:"1.5"}});expect(audio.playbackRate).toBe(1.5);
  audio.currentTime=80;fireEvent.timeUpdate(audio);expect(localStorage.getItem(resumeKey("one"))).toBe("80");fireEvent.ended(audio);expect(localStorage.getItem(resumeKey("one"))).toBeNull();
 });
 it("does not apply a different episode's resume position",()=>{localStorage.setItem(resumeKey("one"),"100");const page=render(<Player url="/audio" generationId="two"/>);const audio=page.getByLabelText("Luvun podcast") as HTMLAudioElement;Object.defineProperty(audio,"duration",{value:120});fireEvent.loadedMetadata(audio);expect(audio.currentTime).toBe(0);});
});
