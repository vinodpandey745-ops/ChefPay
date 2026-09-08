import { Client, type IMessage } from '@stomp/stompjs'

import { useAuthStore } from '@/store/auth'

/** Thin wrapper around the same STOMP-over-WebSocket endpoint the JavaFX client and kitchen
 * display already use (see WebSocketConfig: single `/ws` endpoint, `/topic/*` broker prefix, no
 * SockJS fallback needed - every target client, this one included, is a modern WebSocket-capable
 * client). One shared client per page that needs live updates; call `disconnect()` on unmount. */
export function createStompClient(onConnected: () => void): Client {
  const token = useAuthStore.getState().token
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const client = new Client({
    brokerURL: `${protocol}//${window.location.host}/ws`,
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    reconnectDelay: 4000,
    onConnect: onConnected,
  })
  client.activate()
  return client
}

export function subscribeTopic(client: Client, topic: string, onMessage: (body: unknown) => void) {
  return client.subscribe(topic, (message: IMessage) => {
    try {
      onMessage(JSON.parse(message.body))
    } catch {
      onMessage(message.body)
    }
  })
}
