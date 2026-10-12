// 社区两端共用类型和字符规则；计数按 Unicode 字符，与服务端保持一致。
export const communityCategories=[{value:'experience',label:'养宠心得'},{value:'daily',label:'晒宠日常'},{value:'question',label:'求助问答'}]
export const communityLength=(value:string)=>Array.from(value.trim()).length
export const categoryLabel=(value:string)=>communityCategories.find(c=>c.value===value)?.label||''
export interface CommunityImage {id:string;url:string}
export interface CommunityContent {
 id:string;type:'post'|'review';title:string|null;body:string;category:string|null;rating:number|null
 status:string;authorName:string;isOwner:boolean;images:CommunityImage[];likeCount:number;liked:boolean;favorited:boolean
 createdAt:string;updatedAt:string;orderId?:string
}
export interface CommunityComment {
 id:string;rootId:string|null;replyToId:string|null;replyToName?:string;body:string;status:string
 authorName:string;isShop:boolean;isOwner:boolean;createdAt:string
}
export interface CommunityPage {items:CommunityContent[];total:number;page:number;pageSize:number}
export interface ReviewOrder {id:string;orderNo:string;petName:string;reviewed:boolean}
export interface CommunityNotice {id:string;actorName:string;message:string;read:boolean;createdAt:string;contentId:string;commentId:string;available:boolean}
export interface CommunityNotices {items:CommunityNotice[];unreadCount:number}
export interface CommunityWord {id:string;term:string;enabled:boolean;updatedAt:string}
export interface CommunityReport {id:string;targetId:string;targetType:'content'|'comment';reason:string;status:string;action:string;createdAt:string;targetBody:string;contentId:string}
