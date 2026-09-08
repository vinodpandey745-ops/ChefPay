import { useQueryClient } from '@tanstack/react-query'
import type { QueryKey } from '@tanstack/react-query'
import { useEffect } from 'react'

import { createStompClient, subscribeTopic } from '@/lib/ws'

/**
 * Subscribes to one or more STOMP `/topic/*` channels and invalidates the given react-query keys
 * whenever any event arrives - the server's WsEvent envelope is intentionally "something changed,
 * re-fetch" (see WsEvent's doc comment), so we don't try to hand-merge partial payloads client
 * side; we just let react-query re-pull the REST endpoint that is already the source of truth.
 */
export function useLiveTopics(topics: string[], queryKeys: QueryKey[]) {
  const queryClient = useQueryClient()

  useEffect(() => {
    const client = createStompClient(() => {
      for (const topic of topics) {
        subscribeTopic(client, topic, () => {
          for (const key of queryKeys) {
            queryClient.invalidateQueries({ queryKey: key })
          }
        })
      }
    })

    return () => {
      client.deactivate()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [topics.join(','), JSON.stringify(queryKeys)])
}
