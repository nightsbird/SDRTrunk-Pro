package io.github.dsheirer.dsp.ifilter;

import io.github.dsheirer.sample.complex.ComplexSample;

public class IFConditioner
{
    public IFConditioner(float sampleRate)
    {
        System.out.println("IF SAMPLE RATE = " + sampleRate);
    }

    public ComplexSample process(ComplexSample sample)
    {
        return sample;
    }

    public float processAudio(float audio)
    {
        return audio;
    }
}
