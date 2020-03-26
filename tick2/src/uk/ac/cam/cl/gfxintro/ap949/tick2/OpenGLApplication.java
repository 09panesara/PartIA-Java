package uk.ac.cam.cl.gfxintro.ap949.tick2;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL.createCapabilities;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class OpenGLApplication {

    // Vertical field of view
    private static final float FOV_Y = (float) Math.toRadians(50);
    private static final float HEIGHTMAP_SCALE = 3.0f;

    // Width and height of renderer in pixels
    protected static int WIDTH = 800, HEIGHT = 600;

    // Size of height map in world units
    private static float MAP_SIZE = 10;
    private Camera camera;
    private Texture terrainTexture;
    private long window;

    private ShaderProgram shaders;
    private float[][] heightmap;
    private int no_of_triangles;
    private int vertexArrayObj;

    // Callbacks for input handling
    private GLFWCursorPosCallback cursor_cb;
    private GLFWScrollCallback scroll_cb;
    private GLFWKeyCallback key_cb;

    // Filenames for vertex and fragment shader source code
    private final String VSHADER_FN = "resources/vertex_shader.glsl";
    private final String FSHADER_FN = "resources/fragment_shader.glsl";

    public OpenGLApplication(String heightmapFilename) {

        // Load heightmap data from file into CPU memory
        initializeHeightmap(heightmapFilename);
    }

    // OpenGL setup - do not touch this method!
    public void initializeOpenGL() {

        if (glfwInit() != true)
            throw new RuntimeException("Unable to initialize the graphics runtime.");

        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        // Ensure that the right version of OpenGL is used (at least 3.2)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE); // Use CORE OpenGL profile without depreciated functions
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE); // Make it forward compatible

        window = glfwCreateWindow(WIDTH, HEIGHT, "Tick 3", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the application window.");

        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (mode.width() - WIDTH) / 2, (mode.height() - HEIGHT) / 2);
        glfwMakeContextCurrent(window);
        createCapabilities();

        // Enable v-sync
        glfwSwapInterval(1);

        // Cull back-faces of polygons
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        // Do depth comparisons when rendering
        glEnable(GL_DEPTH_TEST);

        // Create camera, and setup input handlers
        camera = new Camera((double) WIDTH / HEIGHT, FOV_Y);
        initializeInputs();

        // Create shaders and attach to a ShaderProgram
        Shader vertShader = new Shader(GL_VERTEX_SHADER, VSHADER_FN);
        Shader fragShader = new Shader(GL_FRAGMENT_SHADER, FSHADER_FN);
        shaders = new ShaderProgram(vertShader, fragShader, "colour");

        // Initialize mesh data in CPU memory
        float vertPositions[] = initializeVertexPositions( heightmap );
        int indices[] = initializeVertexIndices( heightmap );
        float vertNormals[] = initializeVertexNormals( heightmap );
        float textureCoordinates[] = initializeTextureCoordinates( heightmap );
        no_of_triangles = indices.length;

        // Load mesh data onto GPU memory
        loadDataOntoGPU( vertPositions, indices, vertNormals, textureCoordinates );

        // Load texture image and create OpenGL texture object
        terrainTexture = new Texture();
        terrainTexture.load( "resources/texture.png");
    }

    private void initializeInputs() {

        // Callback for: when dragging the mouse, rotate the camera
        cursor_cb = new GLFWCursorPosCallback() {
            private double prevMouseX, prevMouseY;

            public void invoke(long window, double mouseX, double mouseY) {
                boolean dragging = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
                if (dragging) {
                    camera.rotate(mouseX - prevMouseX, mouseY - prevMouseY);
                }
                prevMouseX = mouseX;
                prevMouseY = mouseY;
            }
        };

        // Callback for: when scrolling, zoom the camera
        scroll_cb = new GLFWScrollCallback() {
            public void invoke(long window, double dx, double dy) {
                camera.zoom(dy > 0);
            }
        };

        // Callback for keyboard controls: "W" - wireframe, "P" - points, "S" - take screenshot
        key_cb = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_W && action == GLFW_PRESS) {
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    glDisable(GL_CULL_FACE);
                } else if (key == GLFW_KEY_P && action == GLFW_PRESS) {
                    glPolygonMode(GL_FRONT_AND_BACK, GL_POINT);
                } else if (key == GLFW_KEY_S && action == GLFW_RELEASE) {
                    takeScreenshot("screenshot.png");
                } else if (action == GLFW_RELEASE) {
                    glEnable(GL_CULL_FACE);
                    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                }
            }
        };

        // Set callbacks on the window
        glfwSetCursorPosCallback(window, cursor_cb);
        glfwSetScrollCallback(window, scroll_cb);
        glfwSetKeyCallback(window, key_cb);
    }

    /**
     * Create an array of vertex positions.
     *
     * @param heightmap 2D array with the heightmap
     * @return Vertex positions in the format { x0, y0, z0, x1, y1, z1, ... }
     */
    public float[] initializeVertexPositions( float[][] heightmap ) {
      //generate and upload vertex data

        int heightmap_width_px = heightmap[0].length;
        int heightmap_height_px = heightmap.length;

        float start_x = -MAP_SIZE / 2;
        float start_z = -MAP_SIZE / 2;
        float delta_x = MAP_SIZE / heightmap_width_px;
        float delta_z = MAP_SIZE / heightmap_height_px;

        // create float array for vertPositions of the right size

        float[] vertPositions = new float[3*heightmap_height_px*heightmap_width_px];



        int index;
        float x, y, z;
        for ( int row = 0; row < heightmap_height_px; row++ ) {
            for ( int col = 0; col < heightmap_width_px; col++ ) {
                // Work out x, y, and z coordinates of vertex
                x = start_x + delta_x * col;
                y = heightmap[row][col];
                z = start_z + delta_z * row;

                // Calculate the index into the vertPositions array
                index = 3 * ( heightmap_width_px * row + col );

                // Set three elements in vertPositions to x, y, and z
                vertPositions[index] = x;
                vertPositions[index + 1] = y;
                vertPositions[index+2] = z;
            }
        }
        return vertPositions;
    }

    public int[] initializeVertexIndices( float[][] heightmap ) {

        //generate and upload index data
        int heightmap_width_px = heightmap[0].length;
        int heightmap_height_px = heightmap.length;

        // create int array for indices of the right size
        int[] indices = new int[6 * (heightmap_width_px - 1) * (heightmap_height_px - 1)];

        int count = 0;
        int vert_index;
        for (int row = 0; row < heightmap_height_px - 1; row++) {
            for (int col = 0; col < heightmap_width_px - 1; col++) {
                // Get vert_index for the corresponding vertex at (row,col)
                vert_index = heightmap_width_px * row + col;

                indices[count++] = vert_index;

                indices[count++] = vert_index + heightmap_width_px;
                indices[count++] = vert_index + heightmap_width_px + 1;

                // Add three indices to index\_count for upper triangle 'B'
                indices[count++] = vert_index;
                indices[count++] = vert_index + heightmap_width_px + 1;
                indices[count++] = vert_index + 1;


            }
        }
        return indices;
    }

    public float[] initializeVertexNormals( float[][] heightmap ) {

        int heightmap_width_px = heightmap[0].length;
        int heightmap_height_px = heightmap.length;
        int num_verts = heightmap_width_px * heightmap_height_px;
        float[] vertNormals = new float[3 * num_verts];

        // Initialize each normal to (0,1,0) so that valid normals can be found at edges
        for(int i=0; i<vertNormals.length; i+=3) {
            vertNormals[i] = 0;
            vertNormals[i+1] = 1;
            vertNormals[i+2] = 0;
        }

        float delta_x = MAP_SIZE / heightmap_width_px;
        float delta_z = MAP_SIZE / heightmap_height_px;
        int index = 0;
        for (int row = 1; row < heightmap_height_px - 1; row++) {
            for (int col = 1; col < heightmap_width_px - 1; col++) {
                // Create Vector3f Tx
                float Tx_y = heightmap[row][col+1] - heightmap[row][col - 1];
                Vector3f Tx = new Vector3f(2 * delta_x, Tx_y, 0 );
                // Create Vector3f Tz
                float Tz_y = heightmap[row+1][col] - heightmap[row-1][col];
                Vector3f Tz = new Vector3f( 0, Tz_y, 2 * delta_z );
                // Calculate Vector3f vertNormal by as the normalized cross product of vecNx and vecNz and put in vertNormals
                Vector3f vertNormal = Tz.cross(Tx).normalize();
                index = 3 * ( heightmap_width_px * row + col );
                vertNormals[index++] = vertNormal.x();
                vertNormals[index++] = vertNormal.y();
                vertNormals[index++] = vertNormal.z();
            }
        }
        return vertNormals;
    }

    public float[][] getHeightmap() {
        return heightmap;
    }

    public void initializeHeightmap(String heightmapFilename) {

        try {
            BufferedImage heightmapImg = ImageIO.read(new File(heightmapFilename));
            int heightmap_width_px = heightmapImg.getWidth();
            int heightmap_height_px = heightmapImg.getHeight();

            heightmap = new float[heightmap_height_px][heightmap_width_px];

            for (int row = 0; row < heightmap_height_px; row++) {
                for (int col = 0; col < heightmap_width_px; col++) {
                    float height = (float) (heightmapImg.getRGB(col, row) & 0xFF) / 0xFF;
                    heightmap[row][col] = HEIGHTMAP_SCALE * (float) Math.pow(height, 2.2);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error loading heightmap");
        }
    }

    private float[] initializeTextureCoordinates( float[][] heightmap ) {

        int heightmapWidthPx = heightmap[0].length;
        int heightmapHeightPx = heightmap.length;

        // create float[] texcoords of the right size
        int numVerts = heightmapWidthPx * heightmapHeightPx;
        float[] texcoords = new float[numVerts*2]; // Note that Java will set all values in the array to 0

        //TODO: Loop over all heightmap pixels and generate texture coordinates
        int index = 0;
        for (int row = 0; row < heightmapHeightPx; row++) {
            for (int col = 0; col < heightmapWidthPx; col++) {
                texcoords[index++] = (float) col/heightmapWidthPx;
                texcoords[index++] = (float) row/heightmapHeightPx;
            }
        }

        return texcoords;
    }


    public void loadDataOntoGPU( float[] vertPositions, int[] indices, float[] vertNormals, float[] textureCoordinates ) {

        int shaders_handle = shaders.getHandle();

        vertexArrayObj = glGenVertexArrays(); // Get a OGL "name" for a vertex-array object
        glBindVertexArray(vertexArrayObj); // Create a new vertex-array object with that name

        // ---------------------------------------------------------------
        // LOAD VERTEX POSITIONS
        // ---------------------------------------------------------------

        // Construct the vertex buffer in CPU memory
        FloatBuffer vertex_buffer = BufferUtils.createFloatBuffer(vertPositions.length);
        vertex_buffer.put(vertPositions); // Put the vertex array into the CPU buffer
        vertex_buffer.flip(); // "flip" is used to change the buffer from read to write mode

        int vertex_handle = glGenBuffers(); // Get an OGL name for a buffer object
        glBindBuffer(GL_ARRAY_BUFFER, vertex_handle); // Bring that buffer object into existence on GPU
        glBufferData(GL_ARRAY_BUFFER, vertex_buffer, GL_STATIC_DRAW); // Load the GPU buffer object with data

        // Get the locations of the "position" vertex attribute variable in our ShaderProgram
        int position_loc = glGetAttribLocation(shaders_handle, "position");

        // If the vertex attribute does not exist, position_loc will be -1, so we should not use it
        if (position_loc != -1) {

            // Specifies where the data for "position" variable can be accessed
            glVertexAttribPointer(position_loc, 3, GL_FLOAT, false, 0, 0);

            // Enable that vertex attribute variable
            glEnableVertexAttribArray(position_loc);
        }

        // ---------------------------------------------------------------
        // LOAD VERTEX NORMALS
        // ---------------------------------------------------------------

        FloatBuffer normal_buffer = BufferUtils.createFloatBuffer(vertNormals.length);
        normal_buffer.put(vertNormals).flip(); // flips state from writing to reading
        int normal_handle = glGenBuffers(); // get handle for new OpenGL buffer for the normals
        glBindBuffer(GL_ARRAY_BUFFER, normal_handle); // bring buffer into existence on GPU
        glBufferData(GL_ARRAY_BUFFER, normal_buffer, GL_STATIC_DRAW); // load buffer with the CPU data from normal_buffer
        int normal_loc = glGetAttribLocation(shaders_handle, "normal"); // get location of normal in shader program
        if (normal_loc != -1) { // if normal variable exists
            glVertexAttribPointer(normal_loc, 3, GL_FLOAT, false, 0, 0); // specify layout for vertex normals
            glEnableVertexAttribArray(normal_loc); // mark attribute as enabled
        }

        // ---------------------------------------------------------------
        // LOAD VERTEX INDICES
        // ---------------------------------------------------------------

        IntBuffer index_buffer = BufferUtils.createIntBuffer(indices.length);
        index_buffer.put(indices).flip();
        int index_handle = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, index_handle);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, index_buffer, GL_STATIC_DRAW);

        // ---------------------------------------------------------------
        // LOAD Texture coordinates
        // ---------------------------------------------------------------

        // Put texture coordinate array into a buffer in CPU memory
        FloatBuffer tex_buffer = BufferUtils.createFloatBuffer(textureCoordinates.length);
        tex_buffer.put(textureCoordinates).flip();

        // Create an OpenGL buffer and load it with texture coordinate data
        int tex_handle = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, tex_handle);
        glBufferData(GL_ARRAY_BUFFER, tex_buffer, GL_STATIC_DRAW);

        // Get the location of the "texcoord" variable in the shader
        int tex_loc = glGetAttribLocation(shaders.getHandle(), "texcoord" );

        // Specify how to access the variable, and enable it
        if (tex_loc != -1) {
            glVertexAttribPointer(tex_loc, 2, GL_FLOAT, false, 0, 0);
            glEnableVertexAttribArray(tex_loc);
        }

        // Finally, check for OpenGL errors
        checkError();
    }


    public void run() {

        initializeOpenGL();

        while (glfwWindowShouldClose(window) != true) {
            render();
        }
    }

    public void render() {

        shaders.reloadIfNeeded(); // If shaders modified on disk, reload them

        // Step 1: Pass a new model-view-projection matrix to the vertex shader

        Matrix4f mvp_matrix; // Model-view-projection matrix
        mvp_matrix = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());

        int mvp_location = glGetUniformLocation(shaders.getHandle(), "mvp_matrix");
        FloatBuffer mvp_buffer = BufferUtils.createFloatBuffer(16);
        mvp_matrix.get(mvp_buffer);
        glUniformMatrix4fv(mvp_location, false, mvp_buffer);

        // Step 2: Clear the buffer

        glClearColor(1.0f, 1.0f, 1.0f, 1.0f); // Set the background colour to dark gray
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Step 3: Draw our VertexArray as triangles

        // TODO: bind texture
        int textureId = terrainTexture.getTexId();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glBindVertexArray(vertexArrayObj); // Bind the existing VertexArray object
        glDrawElements(GL_TRIANGLES, no_of_triangles, GL_UNSIGNED_INT, 0); // Draw it as triangles
        glBindVertexArray(0);              // Remove the binding

        // TODO: Unbind texture
        glBindTexture(GL_TEXTURE_2D, 0);

        // Step 4: Swap the draw and back buffers to display the rendered image

        glfwSwapBuffers(window);
        glfwPollEvents();
        checkError();
    }

//    public void takeScreenshot(String output_path) {
//        int bpp = 4;
//
//        glReadBuffer(GL_FRONT);
//        ByteBuffer buffer = BufferUtils.createByteBuffer(WIDTH * HEIGHT * bpp);
//        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
//        checkError();
//
//        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
//        for (int i = 0; i < WIDTH; ++i) {
//            for (int j = 0; j < HEIGHT; ++j) {
//                int index = (i + WIDTH * (HEIGHT - j - 1)) * bpp;
//                int r = buffer.get(index + 0) & 0xFF;
//                int g = buffer.get(index + 1) & 0xFF;
//                int b = buffer.get(index + 2) & 0xFF;
//                image.setRGB(i, j, 0xFF << 24 | r << 16 | g << 8 | b);
//            }
//        }
//        try {
//            ImageIO.write(image, "png", new File(output_path));
//        } catch (IOException e) {
//            throw new RuntimeException("failed to write output file - ask for a demonstrator");
//        }
//    }

    public void takeScreenshot(String output_path) {
        int bpp = 4;

        int WIDTH = this.WIDTH * 2;
        int HEIGHT = this.HEIGHT * 2;

        glReadBuffer(GL_FRONT);
        ByteBuffer buffer = BufferUtils.createByteBuffer(WIDTH * HEIGHT * bpp);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        checkError();

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < WIDTH; ++i) {
            for (int j = 0; j < HEIGHT; ++j) {
                int index = (i + WIDTH * (HEIGHT - j - 1)) * bpp;
                int r = buffer.get(index + 0) & 0xFF;
                int g = buffer.get(index + 1) & 0xFF;
                int b = buffer.get(index + 2) & 0xFF;
                image.setRGB(i, j, 0xFF << 24 | r << 16 | g << 8 | b);
            }
        }
        try {
            ImageIO.write(image, "png", new File(output_path));
        } catch (IOException e) {
            throw new RuntimeException("failed to write output file - ask for a demonstrator");
        }
    }

    public void stop() {
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void checkError() {
        int error = glGetError();
        if (error != GL_NO_ERROR)
            throw new RuntimeException("OpenGL produced an error (code " + error + ") - ask for a demonstrator");
    }
}
