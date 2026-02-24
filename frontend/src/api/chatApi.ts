import axios from 'axios'

export async function sendMessage(msg: string) {
  const res = await axios.get('http://localhost:8080/chat/personal', {
    params: { msg }
  })
  console.log(res)
  return res.data
}
