"use client";
import {useRef,useState} from "react";
export function resumeKey(generationId:string){return `bookcast:position:${generationId}`;}
export function validPosition(raw:string|null,duration:number){const value=Number(raw);return Number.isFinite(value)&&value>=0?Math.min(value,Math.max(0,duration-1)):0;}
export default function Player({url,generationId}:{url:string;generationId:string}){const audio=useRef<HTMLAudioElement>(null);const [position,setPosition]=useState(0),[duration,setDuration]=useState(0);const key=resumeKey(generationId);
 function save(){if(!audio.current)return;setPosition(audio.current.currentTime);try{localStorage.setItem(key,String(audio.current.currentTime));}catch{}}
 function jump(delta:number){if(audio.current){audio.current.currentTime=Math.max(0,Math.min(audio.current.duration,audio.current.currentTime+delta));save();}}
 const clock=(v:number)=>`${Math.floor(v/60)}:${String(Math.floor(v%60)).padStart(2,"0")}`;
 return <section><h2>Kuuntele</h2><p className="muted">Äänet on tuotettu tekoälyllä. Kuuntelukohta tallentuu tähän selaimeen.</p><audio aria-label="Luvun podcast" controls preload="metadata" ref={audio} src={url} onLoadedMetadata={()=>{const a=audio.current;if(a&&Number.isFinite(a.duration)){setDuration(a.duration);try{a.currentTime=validPosition(localStorage.getItem(key),a.duration);}catch{}setPosition(a.currentTime);}}} onTimeUpdate={save} onPause={save} onEnded={()=>{try{localStorage.removeItem(key);}catch{}}}/><div className="controls"><button onClick={()=>jump(-15)}>−15 s</button><button onClick={()=>jump(15)}>+15 s</button><label htmlFor="speed">Nopeus</label><select id="speed" defaultValue="1" onChange={e=>{if(audio.current)audio.current.playbackRate=Number(e.target.value);}}>{[.75,1,1.25,1.5,1.75,2].map(s=><option key={s} value={s}>{s}×</option>)}</select><span>{clock(position)} / {clock(duration)}</span></div></section>;
}
