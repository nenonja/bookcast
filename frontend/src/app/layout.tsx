import type { Metadata } from "next";
import "./style.css";
export const metadata: Metadata = {title:"Bookcast",description:"Lähteeseen pohjautuva oppimispodcast"};
export default function Layout({children}:{children:React.ReactNode}) {return <html lang="fi"><body><header><a href="/">Bookcast</a><span>Opi kuuntelemalla</span></header><main>{children}</main></body></html>;}
