import { http } from './http'


export async function sendMessage(msg: string) {
  const res = await http.get('/chat/personal', {
    params: { msg }
  })
  return res.data
}


export async function resetUserData(currentUserId: string) {
  const res = await http.delete('/chat/personal/reset', {
    params: { userId: currentUserId }
  })
  return res.data
}

export default {
  name: 'HelloWorld'
}
