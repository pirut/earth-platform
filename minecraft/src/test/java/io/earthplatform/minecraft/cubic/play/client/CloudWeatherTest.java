package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CloudWeatherTest {
    @Test void onePlaceHasClearAndCloudyPeriodsWithGradualTransitions() {
        double low=1,high=0;
        for(double t=0;t<CloudWeather.TIME_PERIOD;t+=1000) {
            double cover=CloudWeather.coverage(12000,9000,t,0);
            low=Math.min(low,cover);high=Math.max(high,cover);
            assertTrue(cover>=0 && cover<=1);
            assertTrue(Math.abs(cover-CloudWeather.coverage(12000,9000,t+1,0))<.001);
            assertTrue(CloudWeather.coverage(12000,9000,t,1)>=cover);
        }
        assertEquals(0,low);assertEquals(1,high);
    }

    @Test void weatherIsWorldAnchoredRepeatableAndStableAtLargeCoordinates() {
        for(double x:new double[] {-40_000_000,-1000,0,12_000,40_000_000}) {
            double expected=CloudWeather.coverage(x,9000,19300,0);
            assertEquals(expected,CloudWeather.coverage(x+CloudWeather.REGION_PERIOD,9000,19300,0),1e-10);
            assertEquals(expected,CloudWeather.coverage(x,9000,19300+CloudWeather.TIME_PERIOD,0),1e-10);
        }
        boolean differs=false;
        for(int i=0;i<20;i++) if(Math.abs(CloudWeather.coverage(i*20000,9000,19300,0)
                -CloudWeather.coverage((i+1)*20000,9000,19300,0))>.1) differs=true;
        assertTrue(differs,"nearby regions need not have the same weather");
    }

    @Test void defaultCloudBandsRemainBelowTheHighestPeaks() {
        for(var band:CloudField.bands(2500)) assertTrue(band.height()+band.thickness()/2<8912);
    }
}
