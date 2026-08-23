package com.trijohn.cloudportal

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeAxisResolverTest {
    private val touchSlop = 10f

    @Test
    fun `does not classify taps or small finger movement`() {
        assertEquals(SwipeAxis.Unresolved, SwipeAxisResolver.resolve(2f, 3f, touchSlop))
        assertEquals(SwipeAxis.Unresolved, SwipeAxisResolver.resolve(7f, 6f, touchSlop))
    }

    @Test
    fun `locks deliberate photo swipes to the horizontal axis`() {
        assertEquals(SwipeAxis.Horizontal, SwipeAxisResolver.resolve(-24f, 6f, touchSlop))
        assertEquals(SwipeAxis.Horizontal, SwipeAxisResolver.resolve(27f, -8f, touchSlop))
    }

    @Test
    fun `keeps page scrolling on the vertical axis`() {
        assertEquals(SwipeAxis.Vertical, SwipeAxisResolver.resolve(5f, -22f, touchSlop))
        assertEquals(SwipeAxis.Vertical, SwipeAxisResolver.resolve(-7f, 26f, touchSlop))
    }

    @Test
    fun `waits through early diagonal jitter then resolves sustained motion`() {
        assertEquals(SwipeAxis.Unresolved, SwipeAxisResolver.resolve(9f, 8f, touchSlop))
        assertEquals(SwipeAxis.Horizontal, SwipeAxisResolver.resolve(18f, 14f, touchSlop))
        assertEquals(SwipeAxis.Vertical, SwipeAxisResolver.resolve(14f, 18f, touchSlop))
    }

    @Test
    fun `maps one completed swipe to one natural gallery direction`() {
        assertEquals(PhotoPage.Next, PhotoSwipePagingPolicy.pageFor(-80f, 40f))
        assertEquals(PhotoPage.Previous, PhotoSwipePagingPolicy.pageFor(80f, 40f))
    }

    @Test
    fun `ignores horizontal movement that is too short to change photos`() {
        assertEquals(null, PhotoSwipePagingPolicy.pageFor(-39f, 40f))
        assertEquals(null, PhotoSwipePagingPolicy.pageFor(39f, 40f))
    }
}
