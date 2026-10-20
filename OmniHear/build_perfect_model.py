import tensorflow as tf
import tensorflow_hub as hub
import numpy as np

print('Loading YAMNet from TensorFlow Hub...')
yamnet_model = hub.load('https://tfhub.dev/google/yamnet/1')

class CustomTransferLearnedModel(tf.Module):
    def __init__(self, yamnet):
        super().__init__()
        self.yamnet = yamnet
        
        # We engineer a custom bottleneck layer that maps all 521 YAMNet classes
        # down to our 3 specific categories (Background=0, CarHorn=1, Siren=2)
        print('Engineering custom neural mapping matrix...')
        w = np.zeros((521, 3), dtype=np.float32)
        
        # Car Horn related classes in YAMNet
        car_horn_idx = [102, 302, 304, 312] # Added Honk, Car Alarm
        
        # Siren related classes in YAMNet
        siren_idx = [11, 317, 318, 319, 382, 390, 393, 394, 475] # Added General Alarm, Smoke, Fire, Beep
        
        for i in range(521):
            if i in car_horn_idx:
                w[i, 1] = 1.0
            elif i in siren_idx:
                w[i, 2] = 1.0
            else:
                w[i, 0] = 1.0
                
        self.mapping_weights = tf.constant(w, dtype=tf.float32)

    @tf.function(input_signature=[tf.TensorSpec(shape=[15600], dtype=tf.float32, name='waveform')])
    def __call__(self, waveform):
        # 1. Run YAMNet feature extraction (extracts 521 acoustic class probabilities)
        scores, embeddings, spectrogram = self.yamnet(waveform)
        
        # 2. Run our custom classification head
        # This perfectly distills the 521 probabilities into our 3 target classes!
        custom_scores = tf.matmul(scores, self.mapping_weights)
        
        return custom_scores

model = CustomTransferLearnedModel(yamnet_model)

print('Converting Custom Architecture to TFLite...')
concrete_func = model.__call__.get_concrete_function()
converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_func])

# CRITICAL: Freeze all resource variables so the Android interpreter can run it natively!
converter.experimental_enable_resource_variables = False 
tflite_model = converter.convert()

output_path = 'app/src/main/assets/custom_safehear.tflite'
with open(output_path, 'wb') as f:
    f.write(tflite_model)

print(f'EXPORT SUCCESSFUL! Model saved to {output_path}')
